# TD — Pagamento Não Confirmado (Fluxo de Exceção)

> Este documento descreve apenas o que diverge de [`td_finished_os.md`](td_finished_os.md). Convenções (outbox, consumo idempotente, guarda de passo, `transaction_register`, filas) estão na seção 0 daquele documento. A integração com o Mercado Pago (Checkout Pro via **Orders API**) está em [`mp_payment_api_integration.md`](../integrations/mp_payment_api_integration.md).

## 1. Quando o fluxo diverge

Depois do passo 15 de `td_finished_os.md`, a ordem está em `AWAITING_PAYMENT` e o cliente recebeu o link de pagamento. O cliente **não conclui o pagamento dentro do prazo de 24h**.

- O Mercado Pago **não notifica** order expirada ou recusada (apenas `order.processed`). Quem detecta o vencimento é um **job interno do `mechanical-hub-billing`**.
- O job **cancela a order no Mercado Pago antes** de declarar falha. O cancelamento só é aceito se a order ainda não foi paga; assim, o Mercado Pago arbitra a corrida entre "prazo vencido" e "cliente pagando no último instante".
- Se o job descobrir que a order foi paga, **não há falha**: o billing publica `paymentConfirmed` e a saga segue o caminho feliz (passo 18).
- Se a falha for confirmada, o billing publica `paymentFailed`. O orquestrador compensa: cancela a ordem (`CANCELED`) e devolve o estoque reservado (`restoreStock`, mesma rotina de [`td_budget_refused.md`](td_budget_refused.md)).
- A saga termina como `COMPENSATED`.

Estado de partida:

| Serviço | Estado |
|---|---|
| orders | `order_status = 'AWAITING_PAYMENT'` |
| orchestrator | `status: 'STARTED'`, `currentStep: 'AWAITING_PAYMENT'`, `payment.link` preenchido, `payment.paymentId: null` |
| stock | Materiais da ordem no registro `RESERVED`, com movimentações `RESERVED` vinculadas ao `orderId` |
| billing | `payments.status = 'PENDING'`, `mp_order_id` e `checkout_url` preenchidos; order no Mercado Pago em `created` |

## 2. Prazos

| Prazo | Valor | Onde |
|---|---|---|
| Prazo de pagamento (negócio) | criação + **24h** | `payments.due_at` — critério de seleção do job |
| Expiração da order no Mercado Pago | criação + **24h05min** | `expiration_time` enviado no `POST /v1/orders`; gravado em `payments.expires_at` |
| Execução do job | a cada **5 min** | `@Scheduled` no billing |

A folga de 5 min garante que a order ainda exista em `created` quando o job a cancela: o cancelamento é a trava que impede pagamentos após o prazo. Se o job atrasar além de 24h05, a order já estará `canceled` no Mercado Pago e o resultado é o mesmo (seção 3, caso B2).

Boleto é excluído na criação da order (`config.payment_method.not_allowed_types = ["ticket"]`), para que nenhum pagamento pendente sobreviva ao prazo.

## 3. Detecção do vencimento (job — `mechanical-hub-billing`)

**F1. Job seleciona pagamentos vencidos**

- Executa apenas na instância líder: `pg_try_advisory_lock` com chave fixa do job (distinta da chave do relay do outbox). Sem o lock → não executa neste ciclo.
- Leitura **sem lock de linha** (as chamadas ao Mercado Pago não ocorrem dentro de transação de banco):

```sql
SELECT payment_id, order_id, transaction_id, mp_order_id
FROM payments
WHERE status = 'PENDING' AND due_at <= now()
ORDER BY due_at
LIMIT 100;
```

**F2. Para cada pagamento, cancela a order no Mercado Pago** (fora da transação)

```http
POST https://api.mercadopago.com/v1/orders/{mp_order_id}/cancel
X-Idempotency-Key: {transactionId}:CANCEL_MP_ORDER
```

| Resultado | Caso | Ação |
|---|---|---|
| `200` (`status = canceled`) | **A** — ninguém pagou; a partir de agora ninguém consegue pagar | Finaliza como falha (F3, `reason = EXPIRED`) |
| `409` (order fora de `created`/`action_required`) | **B** — consultar o estado real | `GET /v1/orders/{mp_order_id}` e segue a tabela abaixo |
| timeout, `5xx`, `429` | Transitório | Nada a fazer; próximo ciclo |
| `401`, `404` | Permanente | Nada a fazer; log de erro e alerta (credencial ou `mp_order_id` inválido) |

Caso B — resultado do `GET /v1/orders/{mp_order_id}`:

| `status` (MP) | Caso | Ação |
|---|---|---|
| `processed` + `status_detail = accredited` | **B1** — o cliente pagou | Finaliza como **sucesso** (F3′): `paymentConfirmed`; a saga segue o passo 18 de `td_finished_os.md` |
| `canceled` | **B2** — a order já expirou no MP | Finaliza como falha (F3, `reason = EXPIRED`) |
| `failed` | **B3** — tentativas esgotadas/recusadas (ex.: `max_attempts_exceeded`) | Finaliza como falha (F3, `reason = FAILED`) |
| `processing` | **B4** — pagamento em análise no MP | Nada a fazer; próximo ciclo. O webhook `order.processed` pode chegar antes |
| `created` / `action_required` | Inconsistência momentânea | Nada a fazer; próximo ciclo |
| timeout, `5xx`, `429` | Transitório | Nada a fazer; próximo ciclo |

**F3. Finaliza como falha** — em uma única transação:
1. `SELECT ... FROM payments WHERE payment_id = :paymentId FOR UPDATE`.
2. Se `payments.status <> 'PENDING'` → nada a fazer (o webhook ou outro ciclo já decidiu); `COMMIT`.
3. `UPDATE payments SET status = 'CANCELED', mp_status = :mpStatus, canceled_at = now()`.
4. `INSERT INTO outbox_events` → `paymentFailed`.

```json
{
  "transactionId": "UUID",
  "orderId": "UUID",
  "reason": "EXPIRED"
}
```
`reason`: `EXPIRED` (casos A e B2) ou `FAILED` (caso B3).

**F3′. Finaliza como sucesso** (caso B1) — em uma única transação, idêntica ao passo 17.3 de `td_finished_os.md`:
1. `SELECT ... FROM payments WHERE payment_id = :paymentId FOR UPDATE`.
2. Se `payments.status <> 'PENDING'` → nada a fazer; `COMMIT`.
3. `UPDATE payments SET status = 'APPROVED', paid_at = now()`.
4. `INSERT INTO outbox_events` → `paymentConfirmed`.

### 3.1 Concorrência entre job e webhook

O Mercado Pago serializa "cancelar" e "pagar" na mesma order: se o cancelamento foi aceito, nenhum pagamento é mais possível; se o pagamento ocorreu, o cancelamento é recusado. Por isso job e webhook nunca chegam a conclusões contraditórias:

| Primeiro no Mercado Pago | Conclusão do job | Conclusão do webhook |
|---|---|---|
| Cancelamento do job | falha (caso A) | não ocorre (order cancelada não recebe pagamento) |
| Pagamento do cliente | sucesso (caso B1) | sucesso |

O único conflito possível é job e webhook concluírem **sucesso** ao mesmo tempo. O lock de linha na escrita final (`FOR UPDATE` + transição condicional `PENDING → estado final`) garante que apenas um deles publique `paymentConfirmed`. O lock **não** é mantido durante as chamadas HTTP ao Mercado Pago.

No webhook (passo 17.3 de `td_finished_os.md`), a condição de "nada a fazer" é `payments.status <> 'PENDING'` (e não apenas `= 'APPROVED'`). Se o Mercado Pago informar `processed` para um pagamento local `CANCELED`, o webhook registra log de erro e alerta (inconsistência — não deve ocorrer pelo desenho acima).

## 4. Compensação (SQS)

**F4. Orquestrador inicia a compensação e envia `cancelOrder`** — `mechanical-hub-orchestrator` → `mechanical-hub-orders`

Em uma única transação:
1. Deduplicação por `eventId`.
2. Guarda de passo + atualização (0.4): `transaction_register.findOneAndUpdate({ _id: :transactionId, currentStep: 'AWAITING_PAYMENT' }, { $set: { status: 'COMPENSATING', currentStep: 'CANCEL_ORDER' } })` — retorno `null` → descarta a mensagem (`DeleteMessage` sem efeito).
3. `outbox_events.insertOne` → `cancelOrder`.

```json
{
  "transactionId": "UUID",
  "orderId": "UUID",
  "idempotencyKey": "{transactionId}:CANCEL_ORDER",
  "reason": "PAYMENT_NOT_CONFIRMED"
}
```

**F5. `mechanical-hub-orders` cancela a ordem e responde `orderCanceled`** — `mechanical-hub-orders` → `mechanical-hub-orchestrator`

Em uma única transação:
1. Deduplicação por `idempotencyKey`.
2. `order.cancel()` — origem obrigatória: `AWAITING_PAYMENT`.
3. `UPDATE service_orders SET order_status = 'CANCELED', version = version + 1`.
4. `INSERT INTO inbox_events` + `INSERT INTO outbox_events` → `orderCanceled`.

```json
{ "transactionId": "UUID", "orderId": "UUID" }
```

Sem notificação ao cliente (decisão vigente: manter apenas as notificações já definidas ; novas notificações ficam como requisito futuro).

**F6. Orquestrador envia `restoreStock`** — `mechanical-hub-orchestrator` → `mechanical-hub-stock`

Em uma única transação:
1. Deduplicação por `eventId`.
2. Guarda de passo + atualização (0.4): `transaction_register.findOneAndUpdate({ _id: :transactionId, currentStep: 'CANCEL_ORDER' }, { $set: { currentStep: 'RESTORE_STOCK' } })` — retorno `null` → descarta a mensagem (`DeleteMessage` sem efeito).
3. `outbox_events.insertOne` → `restoreStock` (materiais lidos de `transaction_register.reservedMaterials`; payload igual ao passo R2 de `td_budget_refused.md`).

**F7. `mechanical-hub-stock` devolve os materiais e responde `stockRestored`**

Idêntico ao passo R3 de `td_budget_refused.md`: devolução `RESERVED → AVAILABLE` com movimentação `RETURN`, seguida da redistribuição FIFO para ordens com pendência de estoque ([`td_stock_pending.md`](td_stock_pending.md)).

**F8. Orquestrador encerra a saga**

Idêntico ao passo R4 de `td_budget_refused.md`:
1. Deduplicação por `eventId`.
2. Guarda de passo + atualização (0.4): `transaction_register.findOneAndUpdate({ _id: :transactionId, currentStep: 'RESTORE_STOCK' }, { $set: { status: 'COMPENSATED', currentStep: null, finishedAt: now } })` — retorno `null` → descarta a mensagem (`DeleteMessage` sem efeito).

Nada a desfazer no `billing`: o orçamento permanece como histórico em `budgets`, e o pagamento já está `CANCELED` em `payments`.

## 5. Status da ordem × status da saga

| Passo | Status da ordem | Saga `status` | Saga `currentStep` | `payments.status` |
|---|---|---|---|---|
| Antes de F1 | `AWAITING_PAYMENT` | `STARTED` | `AWAITING_PAYMENT` | `PENDING` |
| F1–F3 | `AWAITING_PAYMENT` | `STARTED` | `AWAITING_PAYMENT` | `CANCELED` |
| F4–F5 | `CANCELED` | `COMPENSATING` | `CANCEL_ORDER` | `CANCELED` |
| F6–F7 | `CANCELED` | `COMPENSATING` | `RESTORE_STOCK` | `CANCELED` |
| F8 | `CANCELED` | `COMPENSATED` | `NULL` | `CANCELED` |

Caso B1 (pago descoberto pelo job): `payments.status = 'APPROVED'` e o fluxo continua no passo 18 de `td_finished_os.md`.

## 6. Mensagens

| Mensagem | Tipo | Origem | Destino | Transporte | Deduplicação no consumidor |
|---|---|---|---|---|---|
| cancelamento da order | requisição | billing (job) | Mercado Pago | HTTP (`POST /v1/orders/{id}/cancel`) | `X-Idempotency-Key` |
| consulta da order | requisição | billing (job) | Mercado Pago | HTTP (`GET /v1/orders/{id}`) | — (leitura) |
| `paymentFailed` | evento | billing | orchestrator | SQS | `eventId` + guarda de passo (`AWAITING_PAYMENT`) |
| `paymentConfirmed` (caso B1) | evento | billing | orchestrator | SQS | `eventId` + guarda de passo (`AWAITING_PAYMENT`) |
| `cancelOrder` | comando | orchestrator | orders | SQS | `idempotencyKey` |
| `orderCanceled` | resposta | orders | orchestrator | SQS | `eventId` + guarda de passo (`CANCEL_ORDER`) |
| `restoreStock` | comando | orchestrator | stock | SQS | `idempotencyKey` |
| `stockReserved` (outras ordens) | resposta | stock | orchestrator | SQS | `eventId` + guarda de passo (`AWAITING_STOCK` da saga de cada ordem) |
| `stockRestored` | resposta | stock | orchestrator | SQS | `eventId` + guarda de passo (`RESTORE_STOCK`) |

## 7. Impacto em outros documentos

Ajustes aplicados em 2026-10-06 (registro mantido para rastreabilidade).

| Documento | Ajuste |
|---|---|
| `td_finished_os.md` passos 14 e 17 | Migrar de Preferences API para Orders API (`POST /v1/orders`, `checkout_url`, `GET /v1/orders/{id}`, webhook `order.processed`); gravar `due_at`; webhook com condição `status <> 'PENDING'`. |
| `td_finished_os.md` seção 0.1 | `payments`: `mp_order_id`, `checkout_url`, `due_at`, `expires_at`, `status` (`PENDING`, `APPROVED`, `CANCELED`), `mp_status`, `paid_at`, `canceled_at`. |
| `saga_transaction_status.md` | Novo passo `CANCEL_ORDER` (entra ao consumir `paymentFailed`; comando `cancelOrder`; resposta `orderCanceled`); `RESTORE_STOCK` passa a ser alcançado também a partir de `orderCanceled`. |
| `order_status.md` | Transição `AWAITING_PAYMENT → CANCELED` (`order.cancel()`, executada pelo consumo de `cancelOrder`). |
| `failure_handling.md` seção 5 | Endpoints da Orders API; job de expiração: transitórios → próximo ciclo; `401`/`404` → alerta. |
