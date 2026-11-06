# Status da Ordem de Serviço

> Status **exibido aos usuários** da plataforma (mecânico e cliente), mantido por `mechanical-hub-orders`. Não se confunde com o status da saga (`saga_transaction_status.md`), que é controle interno do orquestrador.

Convenção: o valor do enum é em inglês (código e persistência em `service_orders.order_status`); o `displayName` é em português e é o texto exibido na interface.

| Status | displayName | Descrição |
|---|---|---|
| `RECEIVED` | Recebida | Ordem criada pelo mecânico (`POST /orders`). |
| `IN_DIAGNOSIS` | Em diagnóstico | Mecânico iniciou o diagnóstico; reparos sendo adicionados à ordem. |
| `PREPARING` | Em preparação | Diagnóstico finalizado; reserva de estoque e geração de orçamento em andamento. |
| `WAITING_APPROVAL` | Aguardando aprovação | Orçamento pronto; cliente notificado e aguardando sua aprovação. |
| `APPROVED` | Aprovada | Cliente aprovou o orçamento; geração do link de pagamento em andamento. |
| `REJECTED` | Recusada | Cliente recusou o orçamento. |
| `AWAITING_PAYMENT` | Aguardando pagamento | Link de pagamento (Checkout Pro) enviado ao cliente; aguardando confirmação do pagamento. |
| `READY_FOR_EXECUTION` | Liberada para execução | Pagamento confirmado; ordem liberada para o mecânico iniciar a execução. |
| `IN_PROGRESS` | Em execução | Mecânico iniciou a execução dos reparos. |
| `FINISHED` | Finalizada | Mecânico finalizou todos os reparos; materiais utilizados são baixados do estoque. |
| `DELIVERED` | Entregue | Veículo entregue ao cliente. |
| `CANCELED` | Cancelada | Ordem encerrada pelo sistema por um impedimento (ex.: pagamento não confirmado). Diferente de `REJECTED`, que é decisão do cliente. Hoje ocorre a partir de `AWAITING_PAYMENT` (`td_payment_failed.md`). |

```java
public enum OrderStatusEnum {
    RECEIVED("Recebida"),
    IN_DIAGNOSIS("Em diagnóstico"),
    PREPARING("Em preparação"),
    WAITING_APPROVAL("Aguardando aprovação"),
    APPROVED("Aprovada"),
    REJECTED("Recusada"),
    AWAITING_PAYMENT("Aguardando pagamento"),
    READY_FOR_EXECUTION("Liberada para execução"),
    IN_PROGRESS("Em execução"),
    FINISHED("Finalizada"),
    DELIVERED("Entregue"),
    CANCELED("Cancelada");
}
```

## Transições

```
RECEIVED
  → IN_DIAGNOSIS
      → PREPARING
          → WAITING_APPROVAL
              → APPROVED
                  → AWAITING_PAYMENT
                      → READY_FOR_EXECUTION
                          → IN_PROGRESS
                              → FINISHED
                                  → DELIVERED
                      → CANCELED
              → REJECTED
```

## Regras de transição

| Transição | Método de domínio | Quem executa | Regra |
|---|---|---|---|
| `RECEIVED → IN_DIAGNOSIS` | `order.startDiagnosis()` | Mecânico (REST) | — |
| `IN_DIAGNOSIS → PREPARING` | `order.finishDiagnosis()` | Mecânico (REST) | Ordem possui ao menos um reparo. Publica `diagnosisCompleted` (inicia a saga). |
| `PREPARING → WAITING_APPROVAL` | `order.requestApproval()` | Saga (`sendApproval`) | Estoque reservado, orçamento gerado e `has_stock_pending = false`. |
| `WAITING_APPROVAL → APPROVED` | `order.approve()` | Cliente (`POST /mechanical-hub/service-orders/{id}/approve`, com token de aprovação) | Publica `orderApproved`. |
| `WAITING_APPROVAL → REJECTED` | `order.reject()` | Cliente (`POST /mechanical-hub/service-orders/{id}/reject`, com token de aprovação) | Publica `orderRejected` (compensação: devolução do estoque reservado). Status final. |
| `APPROVED → AWAITING_PAYMENT` | `order.awaitPayment()` | Saga (`sendPaymentLink`) | Link de pagamento gerado. |
| `AWAITING_PAYMENT → READY_FOR_EXECUTION` | `order.releaseForExecution()` | Saga (`releaseOrder`) | Pagamento confirmado. |
| `AWAITING_PAYMENT → CANCELED` | `order.cancel()` | Saga (`cancelOrder`) | Pagamento não confirmado no prazo de 24h ([`td_payment_failed.md`](../design/distributedTransaction/td_payment_failed.md)). Publica `orderCanceled` (compensação: devolução do estoque reservado). Status final. |
| `READY_FOR_EXECUTION → IN_PROGRESS` | `order.startExecution()` | Mecânico (REST) | Só a partir de `READY_FOR_EXECUTION` e com `has_stock_pending = false`; em qualquer outro caso o início da execução é rejeitado. |
| `IN_PROGRESS → FINISHED` | `order.finish()` | Mecânico (REST) | Todos os reparos com status `FINISHED`. Publica `orderFinished` (baixa do estoque utilizado). |
| `FINISHED → DELIVERED` | `order.deliver()` | Mecânico (REST) | — |

Transição fora desta tabela é rejeitada (`409 Conflict`) sem gravar alteração nem publicar evento.

## Pendência de estoque (`has_stock_pending`)

Indicador exibido ao mecânico, independente do status. Não é um status da ordem.

| Valor | Descrição |
|---|---|
| `false` | Todos os materiais da ordem estão reservados (ou a reserva ainda não foi avaliada). |
| `true` | Ao menos um material da ordem está sem estoque suficiente; a ordem fica em `PREPARING` e não pode ser enviada para aprovação nem executada. |

| Ação | Método de domínio | Quem executa |
|---|---|---|
| Marcar pendência | `order.markStockPending()` | Saga (`markStockPending`) |
| Remover pendência | `order.clearStockPending()` | Saga (`clearStockPending`) |

Detalhamento em [`td_stock_pending.md`](../design/distributedTransaction/td_stock_pending.md).

## Status do reparo (`TaskStatusEnum`)

| Status | displayName | Descrição |
|---|---|---|
| `PENDING` | Pendente | Reparo adicionado no diagnóstico, ainda não iniciado. |
| `STARTED` | Iniciado | Mecânico iniciou o reparo. |
| `FINISHED` | Finalizado | Reparo concluído. |
