# TD — Orçamento Recusado (Fluxo de Exceção)

> Este documento descreve apenas o que diverge de [`td_finished_os.md`](td_finished_os.md). Convenções (outbox, consumo idempotente, guarda de passo, `transaction_register`, filas) estão na seção 0 daquele documento.

## 1. Quando o fluxo diverge

No passo 12 do `td_finished_os.md`, em vez de aprovar, o cliente **recusa** o orçamento enquanto a ordem está em `WAITING_APPROVAL`.

- `mechanical-hub-orders` encerra a ordem (`REJECTED`) e publica `orderRejected`.
- O orquestrador inicia a compensação: envia `restoreStock` para devolver todos os materiais reservados da ordem.
- O material devolvido é redistribuído para ordens com pendência de estoque (FIFO), mantendo a invariante de prioridade de [`td_stock_pending.md`](td_stock_pending.md).
- A saga termina como `COMPENSATED`.

Estado de partida:

| Serviço | Estado |
|---|---|
| orders | `order_status = 'WAITING_APPROVAL'`, `has_stock_pending = false` |
| orchestrator | `status: 'STARTED'`, `currentStep: 'WAITING_APPROVAL'`, `reservedMaterials` preenchido |
| stock | Materiais da ordem no registro `RESERVED`, com movimentações `RESERVED` vinculadas ao `orderId` |
| billing | Orçamento gerado em `budgets` (histórico; não é alterado pela recusa); nenhum pagamento criado |

## 2. Recusa (REST — `mechanical-hub-orders`)

**R1. Cliente recusa o orçamento** (endpoint público, acessado pelo link da notificação)

```
POST /mechanical-hub/service-orders/{orderId}/reject
X-Approval-Token: {approvalToken}
```
Endpoint dedicado do cliente (`ExternalController`), público como o de aprovação; mesma validação de token do passo 12 de `td_finished_os.md`.

Em uma única transação:
1. Valida `SHA-256(X-Approval-Token)` contra `approval_token_hash`. Token ausente ou inválido → `404 Not Found`. O token não é apagado após a decisão.
2. `order.reject()` — origem obrigatória: `WAITING_APPROVAL`.
3. `UPDATE service_orders SET order_status = 'REJECTED', version = version + 1 WHERE id = :orderId AND version = :version` (aprovação e recusa concorrentes não podem ambas ter sucesso — 0 linhas afetadas → `409 Conflict`).
4. `INSERT INTO outbox_events` → `orderRejected` (usa `saga_transaction_id` da ordem).

Um segundo clique encontra a ordem em `REJECTED`, é rejeitado e não gera novo evento.

`mechanical-hub-orders` → `mechanical-hub-orchestrator`
```json
{ "transactionId": "UUID", "orderId": "UUID" }
```

## 3. Compensação (SQS)

**R2. Orquestrador inicia a compensação e envia `restoreStock`** — `mechanical-hub-orchestrator` → `mechanical-hub-stock`

Em uma única transação:
1. Deduplicação por `eventId`.
2. Guarda de passo + atualização (0.4): `transaction_register.findOneAndUpdate({ _id: :transactionId, currentStep: 'WAITING_APPROVAL' }, { $set: { status: 'COMPENSATING', currentStep: 'RESTORE_STOCK' } })` — retorno `null` → descarta a mensagem (`DeleteMessage` sem efeito).
3. `outbox_events.insertOne` → `restoreStock` (materiais lidos de `transaction_register.reservedMaterials`).

```json
{
  "transactionId": "UUID",
  "orderId": "UUID",
  "idempotencyKey": "{transactionId}:RESTORE_STOCK",
  "reservedMaterials": [
    { "materialId": "UUID", "quantity": 2 },
    { "materialId": "UUID", "quantity": 1 }
  ]
}
```

**R3. `mechanical-hub-stock` devolve os materiais e responde `stockRestored`** — `mechanical-hub-stock` → `mechanical-hub-orchestrator`

Em uma única transação:
1. Deduplicação por `idempotencyKey`.
2. `SELECT pg_advisory_xact_lock(hash(orderId))` — lock da ordem.
3. **Devolução** — para cada material, em ordem crescente de `material_id`:
   1. `SELECT ... FROM stock WHERE material_id = :id AND status IN ('RESERVED', 'AVAILABLE') FOR UPDATE`; valida `RESERVED.quantity >= :qtd`.
   2. `UPDATE stock SET quantity = quantity - :qtd, updated_at = now() WHERE material_id = :id AND status = 'RESERVED'`.
   3. `UPDATE stock SET quantity = quantity + :qtd, updated_at = now() WHERE material_id = :id AND status = 'AVAILABLE'`.
   4. `INSERT INTO stock_movements (material_id, service_order_id = orderId, movement_type = 'RETURN', quantity = :qtd)`.
4. **Redistribuição** — após devolver todos os materiais, para cada material devolvido executa a mesma rotina de resolução de pendências por antiguidade do passo P4.4 de `td_stock_pending.md` (lock por ordem pendente, reserva `min(AVAILABLE, item.quantity)`, movimentação `RESERVED`, baixa/atualização do item, `stockReserved` para cada ordem que zerar suas pendências).
   - A devolução (passo 3) trava todas as linhas de material antes de qualquer lock de outra ordem (passo 4), evitando deadlock com entradas de estoque concorrentes.
5. `INSERT INTO inbox_events` + `INSERT INTO outbox_events` → `stockRestored`.

```json
{ "transactionId": "UUID", "orderId": "UUID" }
```

**R4. Orquestrador encerra a saga**

Em uma única transação:
1. Deduplicação por `eventId`.
2. Guarda de passo + atualização (0.4): `transaction_register.findOneAndUpdate({ _id: :transactionId, currentStep: 'RESTORE_STOCK' }, { $set: { status: 'COMPENSATED', currentStep: null, finishedAt: now } })` — retorno `null` → descarta a mensagem (`DeleteMessage` sem efeito).

## 4. Status da ordem × status da saga

| Passo | Status da ordem | Saga `status` | Saga `currentStep` |
|---|---|---|---|
| Antes de R1 | `WAITING_APPROVAL` | `STARTED` | `WAITING_APPROVAL` |
| R1 | `REJECTED` | `STARTED` | `WAITING_APPROVAL` |
| R2–R3 | `REJECTED` | `COMPENSATING` | `RESTORE_STOCK` |
| R4 | `REJECTED` | `COMPENSATED` | `NULL` |

## 5. Mensagens

| Mensagem | Tipo | Origem | Destino | Transporte | Deduplicação no consumidor |
|---|---|---|---|---|---|
| recusa do orçamento | requisição | cliente | orders | REST (`POST /mechanical-hub/service-orders/{orderId}/reject` + `X-Approval-Token`) | token de aprovação + máquina de estados + versão em `service_orders` |
| `orderRejected` | evento | orders | orchestrator | SQS | `eventId` + guarda de passo (`WAITING_APPROVAL`) |
| `restoreStock` | comando | orchestrator | stock | SQS | `idempotencyKey` |
| `stockReserved` (outras ordens) | resposta | stock | orchestrator | SQS | `eventId` + guarda de passo (`AWAITING_STOCK` da saga de cada ordem) |
| `stockRestored` | resposta | stock | orchestrator | SQS | `eventId` + guarda de passo (`RESTORE_STOCK`) |
