# TD — Pendência de Estoque (Fluxo de Exceção)

> Este documento descreve apenas o que diverge de [`td_finished_os.md`](td_finished_os.md). Convenções (outbox, consumo idempotente, guarda de passo, `transaction_register`) estão na seção 0 daquele documento.

## 1. Quando o fluxo diverge

No passo 7 do `td_finished_os.md` (`mechanical-hub-stock` consome `reserveStock`), **basta um material sem quantidade suficiente** no registro `AVAILABLE` para a ordem entrar em pendência de estoque.

- Materiais com quantidade suficiente são reservados normalmente.
- Materiais sem quantidade suficiente têm reservada a quantidade disponível (se houver) e o restante vira item de pendência.
- Em vez de `stockReserved`, o serviço de estoque publica `stockPending`.
- A saga fica parada até que entradas de estoque cubram todas as pendências da ordem.
- Enquanto houver pendência, a ordem não pode ser enviada para aprovação do cliente nem executada.

## 2. Tabelas envolvidas

| Serviço | Tabela | Uso neste fluxo |
|---|---|---|
| stock | `stock_pending_items` | Uma linha por material pendente da ordem: `id`, `service_order_id` (sem FK), `saga_transaction_id`, `material_id`, `quantity` (quantidade ainda faltante), `created_at`. |
| stock | `stock`, `stock_movements` | Mesmo uso do fluxo principal; entradas geram movimentação `ENTRY`. |
| stock | `idempotency_requests` | Controle de idempotência do `POST /stock/entry`: `idempotency_key` (PK), `request_hash`, `response_status`, `response_body`, `created_at`. |
| orders | `service_orders` | `has_stock_pending` (BOOLEAN) indica a pendência. |
| orchestrator | `transaction_register` (MongoDB) | `currentStep: 'AWAITING_STOCK'` enquanto a pendência não é resolvida. |

`saga_transaction_id` é armazenado em `stock_pending_items` porque a resolução da pendência é disparada por uma requisição REST (entrada de estoque), que não carrega o `transactionId`. É dessa coluna que o serviço de estoque obtém o `transactionId` para publicar o `stockReserved` posteriormente.

## 3. Detecção da pendência

**P1. `mechanical-hub-stock` consome `reserveStock` e identifica falta de material** (substitui o passo 7 do fluxo principal)

Em uma única transação:
1. Deduplicação por `idempotencyKey`.
2. `SELECT material_id, quantity FROM service_materials WHERE service_id IN (:repairIds)`; agrupa por `material_id` somando `quantity`.
3. Para cada material, em ordem crescente de `material_id`:
   1. `SELECT ... FROM stock WHERE material_id = :id AND status = 'AVAILABLE' FOR UPDATE`.
   2. **Quantidade suficiente** → reserva como no fluxo principal:
      - `UPDATE stock SET quantity = quantity - :qtd WHERE material_id = :id AND status = 'AVAILABLE'`;
      - `UPDATE stock SET quantity = quantity + :qtd WHERE material_id = :id AND status = 'RESERVED'` (ou `INSERT` se não existir);
      - `INSERT INTO stock_movements (material_id, service_order_id = orderId, movement_type = 'RESERVED', quantity)`.
   3. **Quantidade insuficiente, ou material sem registro `AVAILABLE`**:
      - `disponivel = AVAILABLE.quantity` (0 se não houver registro); `faltante = necessário - disponivel`.
      - Se `disponivel > 0`, reserva `disponivel` como no item 2 (movimentação `RESERVED` com `quantity = disponivel`). O registro `AVAILABLE` do material fica com `quantity = 0`.
      - `INSERT INTO stock_pending_items (service_order_id = orderId, saga_transaction_id = transactionId, material_id, quantity = faltante)`.
4. Se ao menos um item foi inserido em `stock_pending_items`:
   1. `INSERT INTO inbox_events` + `INSERT INTO outbox_events` → `stockPending`.
   2. Após o `COMMIT`: envia alerta de falta de material aos administradores do estoque (adapter de notificação do `stock`).
5. Se nenhum item ficou pendente → segue o fluxo principal (`stockReserved`).

`mechanical-hub-stock` → `mechanical-hub-orchestrator`
```json
{ "transactionId": "UUID", "orderId": "UUID" }
```
`stockPending` não carrega materiais: o `stock` é o dono dessa informação e só expõe a lista de materiais da ordem no `stockReserved`, quando todos estiverem reservados e o fluxo seguir para o orçamento.

**P2. Orquestrador coloca a saga em espera e envia `markStockPending`** — `mechanical-hub-orchestrator` → `mechanical-hub-orders`

Em uma única transação:
1. Deduplicação por `eventId`.
2. Guarda de passo + atualização (0.4): `transaction_register.findOneAndUpdate({ _id: :transactionId, currentStep: 'RESERVE_STOCK' }, { $set: { currentStep: 'AWAITING_STOCK' } })` — retorno `null` → descarta a mensagem (`DeleteMessage` sem efeito). `status` permanece `STARTED`.
3. `outbox_events.insertOne` → `markStockPending`.

```json
{
  "transactionId": "UUID",
  "orderId": "UUID",
  "idempotencyKey": "{transactionId}:MARK_STOCK_PENDING"
}
```

**P3. `mechanical-hub-orders` marca a ordem com pendência**

Em uma única transação:
1. Deduplicação por `idempotencyKey`.
2. `order.markStockPending()` — origem obrigatória: `PREPARING`.
3. `UPDATE service_orders SET has_stock_pending = true, saga_transaction_id = :transactionId`.
4. `INSERT INTO inbox_events`.

O status da ordem permanece `PREPARING`; a pendência é exibida ao mecânico pelo indicador `has_stock_pending`. O `orders` não armazena quais materiais estão pendentes.

## 4. Bloqueio da ordem

Enquanto `service_orders.has_stock_pending = true`, `mechanical-hub-orders` rejeita (`409 Conflict`):

| Método de domínio | Motivo |
|---|---|
| `order.requestApproval()` | A ordem não pode ser enviada para aprovação do cliente. |
| `order.startExecution()` | A ordem não pode ser executada. |

No fluxo normal nenhum desses comandos chega com a pendência ativa, pois a saga está parada em `AWAITING_STOCK`; a validação no domínio garante a regra independentemente da ordem de chegada das mensagens.

## 5. Resolução da pendência

**P4. Administrador do estoque registra entrada de material** (`mechanical-hub-stock`)

```
POST /stock/entry
Idempotency-Key: 6f1c2a9e-...   (obrigatório; UUID gerado pelo cliente uma vez por submissão)
```
```json
{ "materialId": "UUID", "quantity": 10 }
```

Idempotência (antes de qualquer alteração de estoque, na mesma transação):
1. Sem header `Idempotency-Key` → `400 Bad Request`.
2. `request_hash = hash(materialId, quantity)`.
3. `INSERT INTO idempotency_requests (idempotency_key, request_hash, created_at)`.
   - Violação de PK (chave já usada) → `SELECT ... FROM idempotency_requests WHERE idempotency_key = :key`:
     - `request_hash` igual e `response_status` preenchido → devolve `response_status` + `response_body` armazenados; nada é reprocessado.
     - `request_hash` diferente → `422 Unprocessable Entity` (mesma chave usada para outra entrada).
   - Requisições concorrentes com a mesma chave: a segunda aguarda o lock da PK até a primeira terminar e cai no caso acima.

Entrada e resolução de pendências (mesma transação):
1. `SELECT ... FROM stock WHERE material_id = :materialId AND status = 'AVAILABLE' FOR UPDATE`.
2. `UPDATE stock SET quantity = quantity + :quantity WHERE material_id = :materialId AND status = 'AVAILABLE'`.
3. `INSERT INTO stock_movements (material_id, service_order_id = NULL, movement_type = 'ENTRY', quantity = :quantity)`.
4. Resolução das pendências do material, **por prioridade de antiguidade**:
   1. `SELECT ... FROM stock_pending_items WHERE material_id = :materialId ORDER BY created_at, id FOR UPDATE` — a ordem que espera há mais tempo é atendida primeiro.
   2. Para cada item, enquanto a quantidade `AVAILABLE` for maior que 0:
      0. `SELECT pg_advisory_xact_lock(hash(item.service_order_id))` — lock por ordem, mantido até o `COMMIT`. Serializa entradas concorrentes de materiais diferentes que resolvem pendências da mesma ordem.
      1. `reservar = min(AVAILABLE.quantity, item.quantity)`.
      2. `UPDATE stock SET quantity = quantity - :reservar WHERE material_id = :materialId AND status = 'AVAILABLE'`.
      3. `UPDATE stock SET quantity = quantity + :reservar WHERE material_id = :materialId AND status = 'RESERVED'` (ou `INSERT` se não existir).
      4. `INSERT INTO stock_movements (material_id, service_order_id = item.service_order_id, movement_type = 'RESERVED', quantity = :reservar)`.
      5. Se `reservar = item.quantity` → `DELETE FROM stock_pending_items WHERE id = item.id` e passa ao próximo item.
         Senão → `UPDATE stock_pending_items SET quantity = quantity - :reservar WHERE id = item.id` e encerra o laço (`AVAILABLE` chegou a 0).
   3. Para cada ordem que teve item totalmente resolvido (ainda sob o lock da ordem): `SELECT count(*) FROM stock_pending_items WHERE service_order_id = :orderId`. Como o lock só é liberado no `COMMIT`, uma entrada concorrente de outro material para a mesma ordem só faz essa contagem depois que esta transação terminar — exatamente uma das duas vê `count = 0` e publica `stockReserved`.
      - `count = 0` → a ordem está com todos os materiais reservados:
        1. Monta `reservedMaterials`: `SELECT material_id, SUM(quantity) FROM stock_movements WHERE service_order_id = :orderId AND movement_type = 'RESERVED' GROUP BY material_id`.
        2. `INSERT INTO outbox_events` → `stockReserved` com `transactionId = saga_transaction_id` do item de pendência.
      - `count > 0` → a ordem continua pendente (outros materiais ainda faltam); nenhuma mensagem é publicada.
5. `UPDATE idempotency_requests SET response_status = 201, response_body = :resposta WHERE idempotency_key = :key`.
6. `COMMIT`.

Exemplo — material X, `AVAILABLE = 0`, pendências: ordem A faltando 5 (mais antiga), ordem B faltando 4.

| Evento | `AVAILABLE` | `RESERVED` (X) | Pendência A | Pendência B | Movimentações |
|---|---|---|---|---|---|
| Entrada de 2 | 0 | +2 | 3 | 4 | `ENTRY 2`, `RESERVED 2 (A)` |
| Entrada de 6 | 0 | +6 | resolvida | 1 | `ENTRY 6`, `RESERVED 3 (A)`, `RESERVED 3 (B)` |
| Entrada de 3 | 2 | +1 | — | resolvida | `ENTRY 3`, `RESERVED 1 (B)` |

A quantidade total do material no estoque é sempre `AVAILABLE + RESERVED`; a entrada é somada ao total mesmo quando é integralmente reservada para ordens pendentes.

Uma única entrada pode resolver pendências de várias ordens; cada ordem totalmente atendida gera o seu próprio `stockReserved`.

`mechanical-hub-stock` → `mechanical-hub-orchestrator` (mesmo contrato do fluxo principal, com a lista completa de materiais reservados para a ordem)
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

**P5. Orquestrador libera a pendência e retoma a saga**

Em uma única transação:
1. Deduplicação por `eventId`.
2. Guarda de passo + atualização (0.4): `transaction_register.findOneAndUpdate({ _id: :transactionId, currentStep: 'AWAITING_STOCK' }, { $set: { reservedMaterials: :reservedMaterials, currentStep: 'GENERATE_BUDGET' } })` — retorno `null` → descarta a mensagem (`DeleteMessage` sem efeito).
3. `outbox_events.insertOne` → `clearStockPending` (para `orders`).
4. `outbox_events.insertOne` → `generateBudget` (para `billing`, idêntico ao passo 8 do fluxo principal).

`mechanical-hub-orchestrator` → `mechanical-hub-orders`
```json
{
  "transactionId": "UUID",
  "orderId": "UUID",
  "idempotencyKey": "{transactionId}:CLEAR_STOCK_PENDING"
}
```

**P6. `mechanical-hub-orders` remove a pendência da ordem**

Em uma única transação:
1. Deduplicação por `idempotencyKey`.
2. `order.clearStockPending()`.
3. `UPDATE service_orders SET has_stock_pending = false`.
4. `INSERT INTO inbox_events`.

A partir daqui o fluxo segue o `td_finished_os.md` a partir do passo 9. Como `clearStockPending` e `sendApproval` são publicados na mesma fila de comandos de `orders` (FIFO) com `MessageGroupId = orderId`, `clearStockPending` é sempre consumido antes de `sendApproval`.

## 6. Invariante de prioridade

Para um mesmo material, **se existe item em `stock_pending_items`, a quantidade `AVAILABLE` é 0**:
- na detecção (P1), a ordem pendente consome todo o `AVAILABLE` antes de registrar o faltante;
- na entrada (P4), toda quantidade nova é destinada às pendências antes de sobrar em `AVAILABLE`.

Consequência: uma ordem nova que chega com `reserveStock` (P1) nunca reserva um material que ordens mais antigas estão aguardando — ela encontra `AVAILABLE = 0` e entra no fim da fila de pendências.

## 7. Status da ordem × status da saga

| Passo | Status da ordem | `has_stock_pending` | Saga `status` | Saga `currentStep` |
|---|---|---|---|---|
| P1 | `PREPARING` | `false` | `STARTED` | `RESERVE_STOCK` |
| P2–P3 | `PREPARING` | `true` | `STARTED` | `AWAITING_STOCK` |
| P4 (entrada parcial) | `PREPARING` | `true` | `STARTED` | `AWAITING_STOCK` |
| P4–P5 (pendência zerada) | `PREPARING` | `true` | `STARTED` | `GENERATE_BUDGET` |
| P6 | `PREPARING` | `false` | `STARTED` | `GENERATE_BUDGET` |

## 8. Mensagens

| Mensagem | Tipo | Origem | Destino | Transporte | Deduplicação no consumidor |
|---|---|---|---|---|---|
| `stockPending` | resposta | stock | orchestrator | SQS | `eventId` + guarda de passo (`RESERVE_STOCK`) |
| `markStockPending` | comando | orchestrator | orders | SQS | `idempotencyKey` |
| entrada de estoque | requisição | administrador | stock | REST (`POST /stock/entry`) | header `Idempotency-Key` + `idempotency_requests` |
| `stockReserved` | resposta | stock | orchestrator | SQS | `eventId` + guarda de passo (`AWAITING_STOCK`) |
| `clearStockPending` | comando | orchestrator | orders | SQS | `idempotencyKey` |
