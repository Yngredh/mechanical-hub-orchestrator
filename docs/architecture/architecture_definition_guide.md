# Mechanical Hub — Guia de Definições de Arquitetura (Fase 4)

## 1. Padrão de transação distribuída

**Saga Pattern com orquestração.**

- Um serviço dedicado (`mechanical-hub-orchestrator`) coordena cada transação distribuída do início ao fim.
- O orquestrador é um serviço independente: repositório próprio, deploy próprio, escala própria e banco de dados próprio.
- O estado de cada saga é persistido pelo orquestrador no MongoDB (coleção `transaction_register`, um documento por saga), com `transactionId`, `orderId`, passo atual (`currentStep`) e status da saga; cada mensagem processada é registrada em `transaction_history`.
- Cada comando enviado pelo orquestrador carrega `transactionId` e `idempotencyKey`; cada resposta carrega `transactionId` para correlação.
- Em caso de falha, o orquestrador dispara os comandos de compensação nos serviços envolvidos.

### Status da saga × status da ordem

São conceitos distintos e mantidos separadamente:

| | Status da ordem | Status da saga |
|---|---|---|
| Dono | `mechanical-hub-orders` | `mechanical-hub-orchestrator` |
| Público | Usuários da plataforma (mecânico e cliente) | Interno (controle da transação distribuída) |
| Definição | [`order_status.md`](../business/order_status.md) | [`saga_transaction_status.md`](../design/saga_transaction_status.md) |

Convenção de enums: valores em inglês no código e na persistência; `displayName` em português para exibição na interface (ex.: `FINISHED("Finalizada")`).

A saga começa quando o mecânico conclui o diagnóstico e termina quando o estoque utilizado na execução é baixado. Enquanto aprovação e pagamento não forem concluídos, a ordem não pode entrar em execução.

## 2. Microsserviços

| Serviço | Responsabilidade |
|---|---|
| `mechanical-hub-orchestrator` | Orquestra as transações distribuídas (Saga): registra e acompanha o estado de cada saga, envia comandos, recebe respostas e dispara compensações. |
| `mechanical-hub-orders` | Cria ordens de serviço, altera o status da ordem e expõe a API de interface do sistema (mecânicos e cliente). Responsável pela notificação ao cliente, via adapter. |
| `mechanical-hub-billing` | Calcula o orçamento e executa os pagamentos (integração com Mercado Pago). Único dono dos preços de serviços (mão de obra) e de materiais. |
| `mechanical-hub-stock` | Gerencia as operações de estoque da oficina: relação serviço↔material, quantidades disponíveis e reservadas (`StockStatusEnum`: `AVAILABLE`, `RESERVED`) e movimentações (`StockMovementTypeEnum`: `ENTRY`, `RESERVED`, `RETURN`, `EXCLUDED`, `OUT`). Reserva materiais após o diagnóstico e dá baixa na reserva quando a ordem é finalizada. Não conhece preço. |

### Responsabilidade sobre dados já definida

- **Preço:** `mechanical-hub-billing` é a única fonte de verdade para preço de mão de obra (por reparo) e preço unitário de material.
- **Materiais por serviço e quantidades:** `mechanical-hub-stock` é o dono da relação serviço↔material e das quantidades em estoque.
- **Ordem de serviço e seu status:** `mechanical-hub-orders`.
- **Estado da saga:** `mechanical-hub-orchestrator`.
- Nenhum serviço acessa diretamente o banco de outro serviço.

## 3. Comunicação entre os microsserviços

| Tipo | Tecnologia | Uso |
|---|---|---|
| Síncrona | RESTful HTTP | Interface do sistema com mecânicos e cliente (via `mechanical-hub-orders`); webhook do Mercado Pago (via `mechanical-hub-billing`); chamadas à API do Mercado Pago. |
| Assíncrona | Amazon SQS (filas FIFO) | Toda a comunicação da saga entre o orquestrador e os demais serviços (comandos e respostas). |

- Uma fila de comandos por serviço de destino (`mechanical-hub-orders-commands.fifo`, `mechanical-hub-stock-commands.fifo`, `mechanical-hub-billing-commands.fifo`) e uma fila de respostas/eventos para o orquestrador (`mechanical-hub-orchestrator-events.fifo`), cada uma com sua DLQ.
- `MessageGroupId = orderId`: mensagens de uma mesma ordem para um mesmo serviço são consumidas na ordem de publicação. Decisão e parâmetros em [`infrastructure.md`](../infrastructure/infrastructure.md).

### Idempotência

A entrega do SQS é *at-least-once* (a deduplicação da FIFO cobre só 5 minutos): toda mensagem pode chegar mais de uma vez. A garantia de efeito único é feita em camadas:

| Camada | Mecanismo | Protege contra |
|---|---|---|
| Borda REST (`orders`) | Máquina de estados da ordem: transição a partir de status inválido é rejeitada. | Duplo clique / requisição repetida do mecânico ou do cliente. |
| Borda REST (`stock`) | `POST /stock/entry` exige header `Idempotency-Key`; chave, hash da requisição e resposta gravados em `idempotency_requests` na mesma transação da entrada. | Reenvio da mesma entrada de estoque por engano do usuário. |
| Produtor (todos) | **Transactional outbox** (`outbox_events`, uma tabela — ou coleção, no orquestrador — no banco de cada serviço, não compartilhada): alteração de domínio e mensagem gravadas na mesma transação; cada mensagem recebe um `eventId` estável no momento da gravação. | Evento perdido ou publicado sem a alteração correspondente; republicação mantém o mesmo `eventId`. |
| Consumidor (todos) | `inbox_events` (tabela no banco de orders, stock e billing; no orquestrador, a `transaction_history` com índice único em `messageKey`) com chave `eventId` (eventos) ou `idempotencyKey = {transactionId}:{STEP}` (comandos), gravada na mesma transação do efeito. | Reentrega do SQS e reenvio de comando pelo orquestrador. |
| Orquestrador | Índice único em `orderId` em `transaction_register` + guarda de passo por atualização condicional (`findOneAndUpdate` filtrando `currentStep`). | Segunda saga para a mesma ordem; respostas duplicadas ou fora de ordem. |
| Webhook (`billing`) | Verificação de `payments.status` antes de confirmar. | Notificação repetida do Mercado Pago. |

Detalhamento por passo em [`td_finished_os.md`](../design/distributedTransaction/td_finished_os.md).

### Tratamento de falhas

Retry **bloqueante** com backoff exponencial (1 s → 8 s, 5 retries) para erros transitórios; erros permanentes vão direto para a DLQ (`<fila>-dlq.fifo`, nativa do SQS); outbox nunca descarta mensagens. Parâmetros, classificação de erros e pontos de falha por fluxo em [`failure_handling.md`](../design/failure_handling.md).

## 4. Bancos de dados

Decisão e justificativa na [ADR-0004](../adr/ADR-0004-banco-de-dados-por-microsservico.md); modelo de dados de cada serviço em [`data_model.md`](../design/data_model.md).

| Serviço | Banco | Motivo principal |
|---|---|---|
| `mechanical-hub-orders` | PostgreSQL 16 | Modelo mais relacional do sistema (cliente, veículo, funcionário, ordem, reparos) com consultas por vários atributos. |
| `mechanical-hub-stock` | PostgreSQL 16 | Maior exigência de consistência: saldo concorrente, FIFO de pendências, locks de linha e transações multi-tabela. |
| `mechanical-hub-billing` | PostgreSQL 16 | Agregados relacionados (preços → orçamento → pagamento), transição de pagamento sob lock de linha e consultas financeiras. |
| `mechanical-hub-orchestrator` | MongoDB (replica set) | Um único agregado (a saga), lido e gravado inteiro por chave, com estado semiestruturado que cresce a cada passo e histórico append-only. |

- Entre consistência e disponibilidade, **consistência prevalece em todos os serviços**. No MongoDB: replica set, `writeConcern: majority`, `readConcern: majority` (transações em `snapshot`) e leitura no primário.
- Volume estimado (10 unidades × até 350 ordens/mês × ~15 mensagens por saga) ≈ 1.750 mensagens/dia: escala não foi critério de escolha.
- Pendências de implantação (onde o MongoDB roda, separação física dos PostgreSQL) na ADR-0004.

## 5. Características da arquitetura

- **Resiliente a falhas isoladas:** a falha de um serviço não derruba os demais; o estado da saga é persistido antes de cada comando, permitindo retomar ou compensar a transação.
- **Suporta consistência eventual:** os serviços convergem para um estado consistente ao fim da saga (concluída ou compensada), sem transação distribuída síncrona.

## 6. Sistema de pagamentos

**Checkout Pro do Mercado Pago via Orders API** (`/v1/orders`). Detalhes em [`mp_payment_api_integration.md`](../design/integrations/mp_payment_api_integration.md).

- `mechanical-hub-billing` cria uma *order* na API do Mercado Pago (`POST /v1/orders`) e recebe o `checkout_url` (link de pagamento).
- O link é enviado ao cliente (via `mechanical-hub-orders`); o prazo de pagamento é de 24h (a order expira no Mercado Pago em 24h05min).
- O Mercado Pago notifica `mechanical-hub-billing` pelo webhook `POST /webhooks/payment` (`order.processed`); `billing` valida a assinatura, confirma o pagamento consultando a order (`GET /v1/orders/{id}`) e publica a confirmação para o orquestrador.
- Um job interno de `mechanical-hub-billing` detecta pagamentos pendentes além de 24h, cancela a order no Mercado Pago e publica um evento de falha de pagamento, que leva o orquestrador a compensar a saga (cancelamento da ordem + restauração do estoque). Se a order já tiver sido paga, o job publica a confirmação em vez da falha ([`td_payment_failed.md`](../design/distributedTransaction/td_payment_failed.md)).

## 7. Arquitetura

[`diagrama-arquitetura-microsservicos.png`](diagrama-arquitetura-microsservicos.png) — fluxo do cenário feliz da ordem completa.

## 8. Documentos relacionados

- [`ADR-0004-banco-de-dados-por-microsservico.md`](../adr/ADR-0004-banco-de-dados-por-microsservico.md) — banco de dados de cada microsserviço.
- [`data_model.md`](../design/data_model.md) — modelagem de dados por serviço.
- [`td_finished_os.md`](../design/distributedTransaction/td_finished_os.md) — fluxo do cenário feliz da ordem completa.
- [`td_stock_pending.md`](../design/distributedTransaction/td_stock_pending.md) — fluxo de exceção: pendência de estoque.
- [`td_budget_refused.md`](../design/distributedTransaction/td_budget_refused.md) — fluxo de exceção: orçamento recusado.
- [`td_payment_failed.md`](../design/distributedTransaction/td_payment_failed.md) — fluxo de exceção: pagamento não confirmado.
- [`mp_payment_api_integration.md`](../design/integrations/mp_payment_api_integration.md) — integração com o Mercado Pago (Checkout Pro via Orders API).
- [`saga_transaction_status.md`](../design/saga_transaction_status.md) — status e passos da saga.
- [`order_status.md`](../business/order_status.md) — status da ordem de serviço.
- [`failure_handling.md`](../design/failure_handling.md) — tratamento de falhas sistêmicas (retry, DLQ, outbox, chamadas externas).
