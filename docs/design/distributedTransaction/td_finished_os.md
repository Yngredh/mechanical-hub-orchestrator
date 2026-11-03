# TD — Ordem Completa (Cenário Feliz)

> **Status da ordem ≠ status da saga.**
> - **Status da ordem** (`order_status.md`): pertence a `mechanical-hub-orders` e é exibido aos usuários da plataforma (mecânico e cliente).
> - **Status da saga** (`saga_transaction_status.md`): pertence a `mechanical-hub-orchestrator` e é controle interno da transação distribuída; nunca é exibido ao usuário.

## 0. Convenções técnicas aplicadas em todos os passos

### 0.1 Tabelas e coleções referenciadas

Bancos por serviço definidos na [ADR-0004](../../adr/ADR-0004-banco-de-dados-por-microsservico.md): **PostgreSQL** em `orders`, `stock` e `billing`; **MongoDB** em `orchestrator`. Modelo completo de cada serviço em [`data_model.md`](../data_model.md). Operações nos serviços PostgreSQL estão escritas em SQL; no orquestrador, em operações do MongoDB.

| Serviço | Tabela / coleção | Uso no fluxo |
|---|---|---|
| orders | `service_orders` | Ordem e seu `order_status`; guarda `saga_transaction_id` (recebido no `sendApproval` ou `markStockPending`), `budget`, `has_stock_pending`, `approval_token_hash` e `version` (controle otimista). |
| orders | `order_tasks` | Reparos da ordem (`service_id` = `repairId`, `service_status` = `TaskStatusEnum`). |
| stock | `service_materials` | Materiais e quantidades necessárias por serviço (`service_id` = `repairId`). |
| stock | `stock` | Uma linha por `material_id` + `status` (`StockStatusEnum`: `AVAILABLE`, `RESERVED`). |
| stock | `stock_movements` | Histórico de movimentações (`movement_type` = `StockMovementTypeEnum`), vinculado a `service_order_id` (sem FK — a ordem pertence a `orders`). |
| billing | `budgets` / `budget_items` | Histórico de orçamentos: cada orçamento gerado é uma linha, sem indicador de efetivação (aprovação/pagamento são registrados em `payments`). |
| billing | `payments` | Order do Checkout Pro (Orders API): `mp_order_id`, `checkout_url`, `amount`, `status` (`PENDING`, `APPROVED`, `CANCELED`), `mp_status`, `due_at` (prazo de negócio, criação + 24h), `expires_at` (expiração no MP, criação + 24h05), `paid_at`, `canceled_at`. |
| billing | catálogo de preços | Preço de mão de obra por `repairId` e preço unitário por `materialId`. |
| orchestrator | `transaction_register` (coleção) | Estado da saga, um documento por ordem (ver 0.4). |
| orchestrator | `transaction_history` (coleção) | Histórico append-only das mensagens processadas pela saga (ver 0.4). |
| cada serviço (no próprio banco) | `outbox_events` | Transactional outbox (ver 0.2). Tabela nos serviços PostgreSQL, coleção no orquestrador; nenhuma é compartilhada. |
| orders, stock, billing (no próprio banco) | `inbox_events` | Registro das mensagens recebidas e processadas com sucesso (ver 0.3). No orquestrador esse papel é da `transaction_history` (0.4). Nenhuma é compartilhada. |

### 0.2 Publicação de mensagens (transactional outbox)

Cada serviço possui o seu próprio `outbox_events`, no seu próprio banco, e só ele lê e escreve nele. O único canal assíncrono entre serviços é o Amazon SQS (filas FIFO).

Nenhum serviço publica no SQS diretamente dentro da regra de negócio. A mensagem é gravada no outbox **na mesma transação de banco** em que a alteração de domínio é gravada.

Filas (FIFO): uma fila de comandos por serviço de destino (`mechanical-hub-orders-commands.fifo`, `mechanical-hub-stock-commands.fifo`, `mechanical-hub-billing-commands.fifo`) e uma fila de respostas/eventos para o orquestrador (`mechanical-hub-orchestrator-events.fifo`). Com `MessageGroupId = orderId`, todas as mensagens de uma ordem para um mesmo serviço são consumidas na ordem em que foram publicadas.

#### Serviços PostgreSQL (`orders`, `stock`, `billing`)

1. `INSERT INTO outbox_events (sequence, event_id, aggregate_id, event_type, payload, status, created_at)` com `sequence` = `BIGSERIAL` (ordem de gravação), `event_id = UUID novo`, `aggregate_id = orderId`, `status = 'PENDING'`.
2. `COMMIT`.

Um relay (job/poller) lê `outbox_events` com `status = 'PENDING'` **ordenado por `sequence`**, envia à fila com **`MessageGroupId = orderId`** e `MessageDeduplicationId = event_id` (o `eventId` também vai no envelope JSON), e faz `UPDATE outbox_events SET status = 'SENT'`. Se o relay cair entre publicar e marcar `SENT`, a mensagem é republicada **com o mesmo `eventId`**.

Liderança do relay: `pg_try_advisory_lock` fixo por serviço; a instância que obtém o lock executa o relay, as demais aguardam.

#### Orquestrador (MongoDB)

1. Na transação multi-documento do passo (0.4): `outbox_events.insertOne({ _id: eventId (UUID novo), orderId, seq, eventType, payload, status: 'PENDING', attempts: 0, createdAt: now })`, com `seq` = valor do contador `seq` da saga reservado para este evento (0.4).
2. `commitTransaction`.

O relay lê `outbox_events` com `status: 'PENDING'` ordenado por `createdAt`; **para cada `orderId`, publica em ordem crescente de `seq` e não publica o `seq` n+1 enquanto o n estiver `PENDING`** (índice único `{ orderId, seq }`). Envia com `MessageGroupId = orderId` e `MessageDeduplicationId = _id` (o `eventId` também vai no envelope JSON), e faz `updateOne({ _id }, { $set: { status: 'SENT', sentAt: now } })`. Republicação mantém o mesmo `eventId`. Documentos `SENT` são mantidos (outbox permanente, sem exclusão).

Liderança do relay: lock com expiração (*lease*) em uma coleção de locks (ex.: ShedLock com provider Mongo), no lugar de `pg_try_advisory_lock`.

#### Garantia de ordem por ordem de serviço, com múltiplas instâncias (todos)

- **Relay:** apenas uma instância do serviço publica por vez (lock de liderança acima). O relay envia de forma sequencial e só avança após a confirmação do SQS.
- **Envio:** `MessageDeduplicationId = eventId` faz a FIFO descartar reenvios iguais em até 5 minutos (ex.: relay que caiu entre enviar e marcar `SENT`); depois disso, a deduplicação é da `inbox_events`.
- **SQS FIFO:** mesmo `MessageGroupId` (`orderId`) → entrega em ordem e uma mensagem de cada vez por grupo.
- **Consumidores:** enquanto uma mensagem de um grupo está em processamento (invisível), a FIFO não entrega a seguinte do mesmo grupo a nenhuma instância. Pode haver reentrega (tratada pela idempotência), mas não reordenação.

### 0.3 Consumo idempotente

Envelope JSON de toda mensagem (corpo da mensagem SQS, [`infrastructure.md`](../../infrastructure/infrastructure.md) §4): `eventId` (gerado no outbox do produtor) e, em comandos do orquestrador, `idempotencyKey = {transactionId}:{STEP}` no payload.

- **Chave de deduplicação:** comandos → `idempotencyKey`; eventos → `eventId`.
- Na mesma transação do processamento:
  1. `SELECT response_payload FROM inbox_events WHERE message_key = :key`.
     - Encontrado → não reprocessa; se houver `response_payload`, grava novamente a mesma resposta em `outbox_events` e faz `COMMIT`.
  2. Aplica a regra de negócio.
  3. `INSERT INTO inbox_events (message_key, response_payload, processed_at)` (`message_key` é PK).
  4. Grava a resposta em `outbox_events`.
  5. `COMMIT` → `DeleteMessage` no SQS.

No orquestrador não existe `inbox_events`: a deduplicação é feita pela `transaction_history`, que registra toda mensagem recebida (aplicada ou descartada pela guarda de passo) e tem **índice único em `messageKey`** (= `eventId`). Dentro da transação multi-documento: `transaction_history.findOne({ messageKey: :key })` → encontrado = duplicata, `DeleteMessage` sem efeito; o `insertOne` do histórico é a garantia final (erro de chave duplicada numa corrida também = duplicata). O orquestrador só consome eventos e não armazena resposta: os comandos que publica são consequência da atualização da saga, protegida pela guarda de passo. A `transaction_history` é permanente.

Falhas durante o consumo (retry bloqueante, DLQ): ver [`failure_handling.md`](../failure_handling.md).

### 0.4 Saga no orquestrador (MongoDB)

Coleção `transaction_register`, um documento por saga (modelo completo em [`data_model.md`](../data_model.md) §5):

```json
{
  "_id": "transactionId (UUID)",
  "orderId": "UUID",
  "status": "STARTED",
  "currentStep": "RESERVE_STOCK",
  "seq": 1,
  "repairs": [ { "repairId": "UUID" } ],
  "reservedMaterials": [ { "materialId": "UUID", "quantity": 2 } ],
  "budget": { "budgetId": "UUID", "orderCost": 0.0 },
  "payment": { "link": "https://...", "expiresAt": "ISO-8601", "paymentId": "UUID" },
  "createdAt": "ISO-8601",
  "updatedAt": "ISO-8601",
  "finishedAt": null
}
```

- **Uma saga por ordem:** índice único `{ orderId: 1 }` garante que um segundo `diagnosisCompleted` da mesma ordem não crie outra saga (erro de chave duplicada `E11000`).
- **Guarda de passo:** toda resposta recebida é aplicada com uma atualização condicional atômica. Se o passo atual não for o esperado, nenhum documento é alterado, a mensagem é descartada (`DeleteMessage` sem efeito) — cobre duplicatas e mensagens fora de ordem:

  ```
  transaction_register.findOneAndUpdate(
    { _id: :transactionId, currentStep: '<passo esperado>' },
    { $set: { <campos do passo>, updatedAt: now }, $inc: { seq: <n> } },
    { returnDocument: 'after' })
  ```

  `n` = quantidade de eventos gravados no outbox nesta transação (0, 1 ou 2); os eventos recebem os valores `seq − n + 1 … seq`. Nos passos dos TDs, `updatedAt` e `$inc` ficam implícitos: cada passo mostra apenas o filtro do passo esperado e os campos do `$set`.
- **Transação de cada mensagem** (multi-documento; `writeConcern: majority`, `readConcern: snapshot`), na ordem:
  1. Deduplicação em `transaction_history` por `messageKey` (0.3).
  2. Guarda de passo + atualização da saga (ou `insertOne` na criação, passo 6).
  3. `transaction_history.insertOne({ transactionId, orderId, seq, messageType, messageKey: eventId, stepBefore, stepAfter, outcome: 'APPLIED', receivedAt: now })`.
  4. `outbox_events.insertOne` para cada comando publicado (0.2).
  5. `commitTransaction` → `DeleteMessage` no SQS.

  O item 3 também é a garantia de deduplicação (índice único em `messageKey`). Nos passos dos TDs, o item 3 fica implícito.
- **Mensagem descartada pela guarda de passo:** a transação do efeito é abortada; grava-se apenas `transaction_history` com `outcome: 'DISCARDED_STEP_GUARD'` e faz-se `DeleteMessage`.
- Conflito de escrita entre transações (`TransientTransactionError` / `WriteConflict`) é erro transitório, coberto pelo retry bloqueante do consumidor ([`failure_handling.md`](../failure_handling.md) seção 1).
- Pré-requisitos (ADR-0004): replica set em todos os ambientes (inclusive testes) e `MongoTransactionManager` declarado no Spring.

### 0.5 Transições da ordem

O endpoint `PATCH /orders/{orderId}/status` traduz cada valor para um método de domínio (`order.finishDiagnosis()`, `order.approve()`, …). Cada método valida o status de origem; transição inválida retorna erro (`409 Conflict`) e não grava nada nem publica evento.

## 1. Abertura e diagnóstico (REST — `mechanical-hub-orders`)

**1. Mecânico cria a ordem**

```
POST /orders
```
```json
{
  "customer": {},
  "vehicle": {},
  "requestDescription": ""
}
```
- `INSERT INTO service_orders (..., order_status = 'RECEIVED')`.

**2. Mecânico inicia o diagnóstico**

```
PATCH /orders/{orderId}/status
```
```json
{ "status": "IN_DIAGNOSIS" }
```
- `order.startDiagnosis()` — origem obrigatória: `RECEIVED`.
- `UPDATE service_orders SET order_status = 'IN_DIAGNOSIS'`.

**3. Mecânico adiciona os reparos**

```
PATCH /orders/{orderId}/repairs
```
```json
{
  "repairs": [
    { "repairId": "UUID" },
    { "repairId": "UUID" }
  ]
}
```
- Permitido apenas com a ordem em `IN_DIAGNOSIS`.
- `INSERT INTO order_tasks (service_order_id, service_id = repairId, service_status = 'PENDING')` para cada reparo.

**4. Mecânico finaliza o diagnóstico**

```
PATCH /orders/{orderId}/status
```
```json
{ "status": "PREPARING" }
```
Em uma única transação:
1. `order.finishDiagnosis()` — origem obrigatória: `IN_DIAGNOSIS`; exige ao menos um registro em `order_tasks`.
2. `UPDATE service_orders SET order_status = 'PREPARING'`.
3. `INSERT INTO outbox_events` → `diagnosisCompleted`.

Uma segunda chamada (ex.: duplo clique) encontra a ordem em `PREPARING`, é rejeitada e não gera novo evento.

## 2. Reserva de estoque e orçamento (SQS)

**5. `diagnosisCompleted`** — `mechanical-hub-orders` → `mechanical-hub-orchestrator`

```json
{
  "orderId": "UUID",
  "repairs": [
    { "repairId": "UUID" },
    { "repairId": "UUID" }
  ]
}
```

**6. Orquestrador cria a saga e envia `reserveStock`** — `mechanical-hub-orchestrator` → `mechanical-hub-stock`

Em uma única transação:
1. Deduplicação por `eventId` (0.3).
2. `transaction_register.insertOne({ _id: UUID novo, orderId, status: 'STARTED', currentStep: 'RESERVE_STOCK', seq: 1, repairs, createdAt: now, updatedAt: now })`.
   - Erro de chave duplicada no índice único `orderId` (`E11000`) → saga já existe para a ordem: aborta a transação e descarta a mensagem (`DeleteMessage`).
3. `outbox_events.insertOne` → `reserveStock`.

```json
{
  "transactionId": "UUID",
  "orderId": "UUID",
  "idempotencyKey": "{transactionId}:RESERVE_STOCK",
  "repairs": [
    { "repairId": "UUID" },
    { "repairId": "UUID" }
  ]
}
```

**7. `mechanical-hub-stock` reserva os materiais e responde `stockReserved`** — `mechanical-hub-stock` → `mechanical-hub-orchestrator`

Em uma única transação:
1. Deduplicação por `idempotencyKey` (0.3).
2. `SELECT material_id, quantity FROM service_materials WHERE service_id IN (:repairIds)`; agrupa por `material_id` somando `quantity`.
3. Para cada material, **em ordem crescente de `material_id`** (evita deadlock entre reservas concorrentes):
   1. `SELECT ... FROM stock WHERE material_id = :id AND status = 'AVAILABLE' FOR UPDATE`; valida `quantity >= necessário`. Se algum material não tiver quantidade suficiente → fluxo [`td_stock_pending.md`](td_stock_pending.md).
   2. `UPDATE stock SET quantity = quantity - :qtd, updated_at = now() WHERE material_id = :id AND status = 'AVAILABLE'`.
   3. `UPDATE stock SET quantity = quantity + :qtd, updated_at = now() WHERE material_id = :id AND status = 'RESERVED'` (se a linha `RESERVED` não existir, `INSERT` com `status = 'RESERVED'`).
   4. `INSERT INTO stock_movements (material_id, service_order_id = orderId, movement_type = 'RESERVED', quantity)`.
4. `INSERT INTO inbox_events` + `INSERT INTO outbox_events` → `stockReserved`.

```json
{
  "transactionId": "UUID",
  "orderId": "UUID",
  "reservedMaterials": [
    { "materialId": "UUID", "quantity": 2 },
    { "materialId": "UUID", "quantity": 1 }
  ]
}
```

**8. Orquestrador registra a reserva e envia `generateBudget`** — `mechanical-hub-orchestrator` → `mechanical-hub-billing`

Em uma única transação:
1. Deduplicação por `eventId`.
2. Guarda de passo + atualização (0.4): `transaction_register.findOneAndUpdate({ _id: :transactionId, currentStep: 'RESERVE_STOCK' }, { $set: { reservedMaterials: :reservedMaterials, currentStep: 'GENERATE_BUDGET' } })` — retorno `null` → descarta a mensagem (`DeleteMessage` sem efeito).
3. `outbox_events.insertOne` → `generateBudget`.

```json
{
  "transactionId": "UUID",
  "orderId": "UUID",
  "idempotencyKey": "{transactionId}:GENERATE_BUDGET",
  "repairs": [
    { "repairId": "UUID" },
    { "repairId": "UUID" }
  ],
  "reservedMaterials": [
    { "materialId": "UUID", "quantity": 2 },
    { "materialId": "UUID", "quantity": 1 }
  ]
}
```

**9. `mechanical-hub-billing` calcula o orçamento e responde `budgetGenerated`** — `mechanical-hub-billing` → `mechanical-hub-orchestrator`

Em uma única transação:
1. Deduplicação por `idempotencyKey`.
2. Busca no catálogo de preços o preço de mão de obra de cada `repairId` e o preço unitário de cada `materialId`.
3. Calcula `laborTotal = Σ preço de mão de obra`, `materialTotal = Σ (quantity × preço unitário)`, `orderCost = laborTotal + materialTotal`.
4. `INSERT INTO budgets (budget_id, order_id, transaction_id, labor_total, material_total, total = orderCost, created_at)` e `INSERT INTO budget_items` (uma linha por reparo e por material, com preço vigente no momento).
5. `INSERT INTO inbox_events` + `INSERT INTO outbox_events` → `budgetGenerated`.

```json
{
  "transactionId": "UUID",
  "orderId": "UUID",
  "budgetId": "UUID",
  "orderCost": 0.0
}
```

**10. Orquestrador envia `sendApproval`** — `mechanical-hub-orchestrator` → `mechanical-hub-orders`

Em uma única transação:
1. Deduplicação por `eventId`.
2. Guarda de passo + atualização (0.4): `transaction_register.findOneAndUpdate({ _id: :transactionId, currentStep: 'GENERATE_BUDGET' }, { $set: { budget: { budgetId, orderCost }, currentStep: 'WAITING_APPROVAL' } })` — retorno `null` → descarta a mensagem (`DeleteMessage` sem efeito).
3. `outbox_events.insertOne` → `sendApproval`.

```json
{
  "transactionId": "UUID",
  "orderId": "UUID",
  "idempotencyKey": "{transactionId}:SEND_APPROVAL",
  "budgetId": "UUID",
  "orderCost": 0.0
}
```

**11. `mechanical-hub-orders` solicita a aprovação**

Em uma única transação:
1. Deduplicação por `idempotencyKey`.
2. `order.requestApproval()` — origem obrigatória: `PREPARING` e `has_stock_pending = false`.
3. Gera o token de aprovação: `approvalToken` = 32 bytes aleatórios (`SecureRandom`, codificados em base64url).
4. `UPDATE service_orders SET order_status = 'WAITING_APPROVAL', budget = :orderCost, saga_transaction_id = :transactionId, approval_token_hash = SHA-256(approvalToken)`. O token em texto puro não é persistido.
5. `INSERT INTO inbox_events`.

Após o `COMMIT`: envia a notificação ao cliente (adapter de notificação) com o resumo da ordem, o orçamento e o link de decisão contendo o token (`https://<frontend>/orders/{orderId}/approval?token={approvalToken}`).

O token é permanente (não expira nem é apagado) e autentica o cliente em todos os endpoints externos da ordem:

| Endpoint | Uso |
|---|---|
| `GET /mechanical-hub/service-orders/{orderNumber}` + `X-Approval-Token` | Consulta da ordem (resumo, status, orçamento) a qualquer momento após o orçamento |
| `POST /mechanical-hub/service-orders/{orderId}/approve` + `X-Approval-Token` | Aprovação (somente em `WAITING_APPROVAL`) |
| `POST /mechanical-hub/service-orders/{orderId}/reject` + `X-Approval-Token` | Recusa (somente em `WAITING_APPROVAL`) |

Token ausente ou inválido → `404 Not Found` em todos. Opções de evolução (expiração, tokens separados) registradas como requisito futuro (FR-07).

Em reprocessamento de `sendApproval` (deduplicado por `idempotencyKey`), nenhum token novo é gerado.

## 3. Aprovação e pagamento

**12. Cliente aprova o orçamento** (endpoint público, acessado pelo link da notificação)

```
POST /mechanical-hub/service-orders/{orderId}/approve
X-Approval-Token: {approvalToken}
```
- Endpoint dedicado do cliente (`ExternalController`), já público no API Gateway (prefixo `mechanical-hub` em `local.public_paths`) e no Spring Security (`/mechanical-hub/service-orders/**` → `permitAll`). Sem body: a ação é definida pela rota.
- A rota de alteração de status (`PATCH /orders/{orderId}/status`) continua exclusiva de mecânicos e administradores, protegida pelo autorizador JWT; o cliente não a acessa.

Em uma única transação:
1. `SELECT ... FROM service_orders WHERE id = :orderId`; valida `SHA-256(X-Approval-Token)` contra `approval_token_hash` (comparação em tempo constante). Token ausente ou inválido → `404 Not Found` (não revela se a ordem existe). O token não é apagado após a decisão: continua válido para consulta da ordem (ver abaixo).
2. `order.approve()` — origem obrigatória: `WAITING_APPROVAL`.
3. `UPDATE service_orders SET order_status = 'APPROVED', version = version + 1 WHERE id = :orderId AND version = :version` (controle otimista contra decisões concorrentes — 0 linhas afetadas → `409 Conflict`). A decisão única é garantida pela máquina de estados: com token válido e a ordem fora de `WAITING_APPROVAL` → `409 Conflict`.
4. `INSERT INTO outbox_events` → `orderApproved` (usa `saga_transaction_id` da ordem).

Um segundo clique encontra a ordem em `APPROVED`, é rejeitado e não gera novo evento.

```json
{ "transactionId": "UUID", "orderId": "UUID" }
```

**13. Orquestrador envia `generatePaymentLink`** — `mechanical-hub-orchestrator` → `mechanical-hub-billing`

Em uma única transação:
1. Deduplicação por `eventId`.
2. Guarda de passo + atualização (0.4): `transaction_register.findOneAndUpdate({ _id: :transactionId, currentStep: 'WAITING_APPROVAL' }, { $set: { currentStep: 'AWAITING_PAYMENT' } })` — retorno `null` → descarta a mensagem (`DeleteMessage` sem efeito).
3. `outbox_events.insertOne` → `generatePaymentLink`.

```json
{
  "transactionId": "UUID",
  "orderId": "UUID",
  "idempotencyKey": "{transactionId}:GENERATE_PAYMENT_LINK",
  "budgetId": "UUID",
  "orderCost": 0.0
}
```

**14. `mechanical-hub-billing` cria a order no Checkout Pro (Orders API) e responde `paymentLinkGenerated`** — `mechanical-hub-billing` → `mechanical-hub-orchestrator`

Detalhes da integração em [`mp_payment_api_integration.md`](../integrations/mp_payment_api_integration.md) (A).

1. Deduplicação por `idempotencyKey`.
2. `SELECT ... FROM payments WHERE transaction_id = :transactionId AND status = 'PENDING'`. Se existir, reutiliza `checkout_url`/`due_at` e segue para o passo 4.
3. Chama a API do Mercado Pago (fora da transação de banco): `POST /v1/orders` com header `X-Idempotency-Key: {transactionId}:GENERATE_PAYMENT_LINK`, `type = "online"`, `processing_mode = "manual"`, `external_reference = orderId`, `total_amount` e item único com valor `orderCost`, `expiration_time = now + 24h05min` e boleto excluído (`config.payment_method.not_allowed_types = ["ticket"]`). Recebe `id` (order no MP) e `checkout_url`.
4. Em uma única transação:
   1. `INSERT INTO payments (payment_id, order_id, transaction_id, budget_id, mp_order_id = id, checkout_url, amount = orderCost, status = 'PENDING', mp_status = 'created', due_at = now + 24h, expires_at = now + 24h05min, created_at)` (se ainda não existir).
   2. `INSERT INTO inbox_events` + `INSERT INTO outbox_events` → `paymentLinkGenerated` (`paymentLink = checkout_url`, `expiresAt = due_at`).

```json
{
  "transactionId": "UUID",
  "orderId": "UUID",
  "paymentLink": "https://...",
  "expiresAt": "2026-09-28T00:00:00Z"
}
```

**15. Orquestrador envia `sendPaymentLink`** — `mechanical-hub-orchestrator` → `mechanical-hub-orders`

Orquestrador, em uma única transação:
1. Deduplicação por `eventId`.
2. Guarda de passo + atualização (0.4): `transaction_register.findOneAndUpdate({ _id: :transactionId, currentStep: 'AWAITING_PAYMENT', 'payment.link': null }, { $set: { 'payment.link': :paymentLink, 'payment.expiresAt': :expiresAt } })` — retorno `null` → descarta a mensagem (`DeleteMessage` sem efeito).
3. `outbox_events.insertOne` → `sendPaymentLink`.

```json
{
  "transactionId": "UUID",
  "orderId": "UUID",
  "idempotencyKey": "{transactionId}:SEND_PAYMENT_LINK",
  "paymentLink": "https://..."
}
```

`mechanical-hub-orders`, em uma única transação:
1. Deduplicação por `idempotencyKey`.
2. `order.awaitPayment()` — origem obrigatória: `APPROVED`.
3. `UPDATE service_orders SET order_status = 'AWAITING_PAYMENT'`.
4. `INSERT INTO inbox_events`.

Após o `COMMIT`: notifica o cliente com o link de pagamento.

**16. Cliente paga no Mercado Pago.**

**17. Mercado Pago notifica `mechanical-hub-billing`**

```
POST /webhooks/payment
```
1. Valida a assinatura da notificação (header `x-signature`, HMAC SHA-256 com `MP_WEBHOOK_SECRET`). Inválida → `401`.
2. Lê `data.id` (id da order no MP) e consulta `GET /v1/orders/{id}` na API do Mercado Pago (não confia apenas no corpo da notificação).
3. Se `status = 'processed'` e `status_detail = 'accredited'`, em uma única transação:
   1. `SELECT ... FROM payments WHERE mp_order_id = :id FOR UPDATE`.
   2. Se `payments.status <> 'PENDING'` → nada a fazer (notificação repetida, ou o job de expiração já decidiu). Se for `'CANCELED'`, registra log de erro e alerta (inconsistência — ver [`td_payment_failed.md`](td_payment_failed.md) 3.1).
   3. `UPDATE payments SET status = 'APPROVED', mp_status = 'processed', paid_at = now()`.
   4. `INSERT INTO outbox_events` → `paymentConfirmed` (`paymentId = payments.payment_id`).
4. Responde `200` ao Mercado Pago em até 22 s (o MP reenvia a notificação enquanto não receber sucesso).

```json
{ "transactionId": "UUID", "orderId": "UUID", "paymentId": "UUID" }
```

**18. Orquestrador envia `releaseOrder`** — `mechanical-hub-orchestrator` → `mechanical-hub-orders`

Orquestrador, em uma única transação:
1. Deduplicação por `eventId`.
2. Guarda de passo + atualização (0.4): `transaction_register.findOneAndUpdate({ _id: :transactionId, currentStep: 'AWAITING_PAYMENT' }, { $set: { 'payment.paymentId': :paymentId, currentStep: 'AWAITING_EXECUTION' } })` — retorno `null` → descarta a mensagem (`DeleteMessage` sem efeito).
3. `outbox_events.insertOne` → `releaseOrder`.

```json
{
  "transactionId": "UUID",
  "orderId": "UUID",
  "idempotencyKey": "{transactionId}:RELEASE_ORDER"
}
```

`mechanical-hub-orders`, em uma única transação:
1. Deduplicação por `idempotencyKey`.
2. `order.releaseForExecution()` — origem obrigatória: `AWAITING_PAYMENT`.
3. `UPDATE service_orders SET order_status = 'READY_FOR_EXECUTION'`.
4. `INSERT INTO inbox_events`.

Após o `COMMIT`: notifica o mecânico de que a ordem está liberada para execução.

## 4. Execução e finalização

**19. Mecânico inicia a execução**

```
PATCH /orders/{orderId}/status
```
```json
{ "status": "IN_PROGRESS" }
```
- `order.startExecution()` — origem obrigatória: `READY_FOR_EXECUTION` e `has_stock_pending = false`. Em qualquer outro caso a requisição é rejeitada.
- `UPDATE service_orders SET order_status = 'IN_PROGRESS'`.
- Durante a execução o mecânico atualiza `order_tasks.service_status` (`PENDING → STARTED → FINISHED`). A atualização de reparos **não** movimenta estoque.

**20. Mecânico finaliza a ordem**

```
PATCH /orders/{orderId}/status
```
```json
{ "status": "FINISHED" }
```
Em uma única transação:
1. `order.finish()` — origem obrigatória: `IN_PROGRESS`; exige todos os `order_tasks` com `service_status = 'FINISHED'`.
2. `UPDATE service_orders SET order_status = 'FINISHED', completed_at = now()`.
3. `INSERT INTO outbox_events` → `orderFinished`.

```json
{ "transactionId": "UUID", "orderId": "UUID" }
```

**21. Orquestrador envia `consumeStock`** — `mechanical-hub-orchestrator` → `mechanical-hub-stock`

Em uma única transação:
1. Deduplicação por `eventId`.
2. Guarda de passo + atualização (0.4): `transaction_register.findOneAndUpdate({ _id: :transactionId, currentStep: 'AWAITING_EXECUTION' }, { $set: { currentStep: 'CONSUME_STOCK' } })` — retorno `null` → descarta a mensagem (`DeleteMessage` sem efeito).
3. `outbox_events.insertOne` → `consumeStock` (materiais lidos de `transaction_register.reservedMaterials`).

```json
{
  "transactionId": "UUID",
  "orderId": "UUID",
  "idempotencyKey": "{transactionId}:CONSUME_STOCK",
  "reservedMaterials": [
    { "materialId": "UUID", "quantity": 2 },
    { "materialId": "UUID", "quantity": 1 }
  ]
}
```

**22. `mechanical-hub-stock` baixa os materiais utilizados e responde `stockConsumed`** — `mechanical-hub-stock` → `mechanical-hub-orchestrator`

Em uma única transação:
1. Deduplicação por `idempotencyKey`.
2. Para cada material, em ordem crescente de `material_id`:
   1. `SELECT ... FROM stock WHERE material_id = :id AND status = 'RESERVED' FOR UPDATE`; valida `quantity >= :qtd`.
   2. `UPDATE stock SET quantity = quantity - :qtd, updated_at = now() WHERE material_id = :id AND status = 'RESERVED'` — a reserva da ordem é zerada.
   3. `INSERT INTO stock_movements (material_id, service_order_id = orderId, movement_type = 'OUT', quantity)`.
   - A linha `AVAILABLE` não é alterada (a quantidade já foi descontada na reserva).
3. `INSERT INTO inbox_events` + `INSERT INTO outbox_events` → `stockConsumed`.

```json
{ "transactionId": "UUID", "orderId": "UUID" }
```

**23. Orquestrador encerra a saga**

1. Deduplicação por `eventId`.
2. Guarda de passo + atualização (0.4): `transaction_register.findOneAndUpdate({ _id: :transactionId, currentStep: 'CONSUME_STOCK' }, { $set: { status: 'COMPLETED', currentStep: null, finishedAt: now } })` — retorno `null` → descarta a mensagem (`DeleteMessage` sem efeito).

**24. Mecânico entrega o veículo**

```
PATCH /orders/{orderId}/status
```
```json
{ "status": "DELIVERED" }
```
- `order.deliver()` — origem obrigatória: `FINISHED`.
- `UPDATE service_orders SET order_status = 'DELIVERED', delivered_at = now()`.

## 5. Status da ordem × status da saga por passo

| Passo | Status da ordem (visível ao usuário) | Saga `status` (interno) | Saga `currentStep` (interno) |
|---|---|---|---|
| 1 | `RECEIVED` | — | — |
| 2–3 | `IN_DIAGNOSIS` | — | — |
| 4–5 | `PREPARING` | — | — |
| 6–7 | `PREPARING` | `STARTED` | `RESERVE_STOCK` |
| 8–9 | `PREPARING` | `STARTED` | `GENERATE_BUDGET` |
| 10–11 | `WAITING_APPROVAL` | `STARTED` | `WAITING_APPROVAL` |
| 12 | `APPROVED` | `STARTED` | `WAITING_APPROVAL` |
| 13–17 | `AWAITING_PAYMENT` | `STARTED` | `AWAITING_PAYMENT` |
| 18 | `READY_FOR_EXECUTION` | `STARTED` | `AWAITING_EXECUTION` |
| 19 | `IN_PROGRESS` | `STARTED` | `AWAITING_EXECUTION` |
| 20–22 | `FINISHED` | `STARTED` | `CONSUME_STOCK` |
| 23 | `FINISHED` | `COMPLETED` | `NULL` |
| 24 | `DELIVERED` | `COMPLETED` | `NULL` |

## 6. Mensagens

| Mensagem | Tipo | Origem | Destino | Transporte | Deduplicação no consumidor |
|---|---|---|---|---|---|
| `diagnosisCompleted` | evento | orders | orchestrator | SQS | `eventId` + índice único em `orderId` |
| `reserveStock` | comando | orchestrator | stock | SQS | `idempotencyKey` |
| `stockReserved` | resposta | stock | orchestrator | SQS | `eventId` + guarda de passo |
| `generateBudget` | comando | orchestrator | billing | SQS | `idempotencyKey` |
| `budgetGenerated` | resposta | billing | orchestrator | SQS | `eventId` + guarda de passo |
| `sendApproval` | comando | orchestrator | orders | SQS | `idempotencyKey` |
| `orderApproved` | evento | orders | orchestrator | SQS | `eventId` + guarda de passo |
| `generatePaymentLink` | comando | orchestrator | billing | SQS | `idempotencyKey` |
| `paymentLinkGenerated` | resposta | billing | orchestrator | SQS | `eventId` + guarda de passo |
| `sendPaymentLink` | comando | orchestrator | orders | SQS | `idempotencyKey` |
| webhook de pagamento (`order.processed`) | notificação | Mercado Pago | billing | HTTP | `payments.status` (`PENDING` → estado final) |
| `paymentConfirmed` | evento | billing | orchestrator | SQS | `eventId` + guarda de passo |
| `releaseOrder` | comando | orchestrator | orders | SQS | `idempotencyKey` |
| `orderFinished` | evento | orders | orchestrator | SQS | `eventId` + guarda de passo |
| `consumeStock` | comando | orchestrator | stock | SQS | `idempotencyKey` |
| `stockConsumed` | resposta | stock | orchestrator | SQS | `eventId` + guarda de passo |

Todas as filas são FIFO com `MessageGroupId = orderId`. Mensagens do fluxo de pendência de estoque: ver [`td_stock_pending.md`](td_stock_pending.md).
