# Status da Saga (transaction_register)

> Controle **interno** de `mechanical-hub-orchestrator`. Não é exibido aos usuários da plataforma e não se confunde com o status da ordem (`order_status.md`), que pertence a `mechanical-hub-orders`.

## Coleção `transaction_register` (MongoDB)

Um documento por saga. Banco definido na [ADR-0004](../adr/ADR-0004-banco-de-dados-por-microsservico.md); modelo completo, índices e coleções auxiliares (`transaction_history`, `outbox_events`) em [`data_model.md`](data_model.md) §5.

| Campo | Descrição |
|---|---|
| `_id` | `transactionId` (UUID). Gerado pelo orquestrador ao criar a saga. |
| `orderId` | **Índice único** — no máximo uma saga por ordem. |
| `status` | Status da transação (ver abaixo). |
| `currentStep` | Passo atual da saga (ver abaixo); `null` quando a saga é encerrada. |
| `seq` | Contador de eventos gravados no outbox pela saga; define a ordem de publicação por `orderId` (`td_finished_os.md` 0.2 e 0.4). |
| `repairs` | Lista `[{ repairId }]` recebida em `diagnosisCompleted`. |
| `reservedMaterials` | Lista `[{ materialId, quantity }]` recebida em `stockReserved`; usada no `consumeStock` e no `restoreStock`. |
| `budget` | `{ budgetId, orderCost }`, recebido em `budgetGenerated`. |
| `payment` | `{ link, expiresAt }` recebidos em `paymentLinkGenerated`; `paymentId` recebido em `paymentConfirmed`. |
| `createdAt`, `updatedAt`, `finishedAt` | Controle temporal. |

Cada mensagem processada pela saga também gera um documento em `transaction_history` (passo antes e depois, resultado, momento de chegada).

## Status da transação (`status`)

| Status | Descrição |
|---|---|
| `STARTED` | Saga criada ao consumir `diagnosisCompleted`; transação em andamento até a baixa do estoque utilizado. |
| `COMPLETED` | Todos os passos concluídos: ordem paga, executada, finalizada e estoque utilizado baixado. |
| `COMPENSATING` | Uma falha foi detectada; o orquestrador está executando os comandos de compensação (cancelamento da ordem, restauração do estoque). |
| `COMPENSATED` | Compensação concluída; a saga foi encerrada sem sucesso e os serviços voltaram a um estado consistente. |

## Passo atual (`currentStep`)

| Passo | Entra no passo ao consumir | Comando publicado | Resposta esperada (guarda de passo) |
|---|---|---|---|
| `RESERVE_STOCK` | `diagnosisCompleted` | `reserveStock` → stock | `stockReserved` ou `stockPending` |
| `AWAITING_STOCK` | `stockPending` | `markStockPending` → orders | `stockReserved` (publicado quando entradas de estoque resolvem todas as pendências da ordem) |
| `GENERATE_BUDGET` | `stockReserved` (com `currentStep` = `RESERVE_STOCK` ou `AWAITING_STOCK`; neste último caso publica também `clearStockPending` → orders) | `generateBudget` → billing | `budgetGenerated` |
| `WAITING_APPROVAL` | `budgetGenerated` | `sendApproval` → orders | `orderApproved` ou `orderRejected` |
| `AWAITING_PAYMENT` | `orderApproved` | `generatePaymentLink` → billing; depois `sendPaymentLink` → orders | `paymentLinkGenerated`, depois `paymentConfirmed` ou `paymentFailed` |
| `AWAITING_EXECUTION` | `paymentConfirmed` | `releaseOrder` → orders | `orderFinished` |
| `CONSUME_STOCK` | `orderFinished` | `consumeStock` → stock | `stockConsumed` |
| `null` | `stockConsumed` | — | — (`status = COMPLETED`) |
| `CANCEL_ORDER` | `paymentFailed` (saga → `COMPENSATING`) | `cancelOrder` → orders | `orderCanceled` |
| `RESTORE_STOCK` | `orderRejected` (saga → `COMPENSATING`) ou `orderCanceled` | `restoreStock` → stock | `stockRestored` |
| `null` | `stockRestored` | — | — (`status = COMPENSATED`) |

Mensagem cuja resposta não corresponde ao `currentStep` atual é descartada (`DeleteMessage` sem efeito) e registrada em `transaction_history` com `outcome: 'DISCARDED_STEP_GUARD'`. A guarda é aplicada com `findOneAndUpdate` condicional ao passo esperado (`td_finished_os.md` 0.4).

Fluxos de compensação: [`td_budget_refused.md`](distributedTransaction/td_budget_refused.md) (recusa do orçamento) e [`td_payment_failed.md`](distributedTransaction/td_payment_failed.md) (pagamento não confirmado).
