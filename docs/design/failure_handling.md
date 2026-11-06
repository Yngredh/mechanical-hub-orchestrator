# Tratamento de Falhas Sistêmicas

Define como os quatro serviços reagem a falhas de infraestrutura e de processamento (banco, broker, rede, serviços externos, mensagens inválidas). Mensageria: Amazon SQS FIFO. Bancos: PostgreSQL em `orders`, `stock` e `billing`; MongoDB em `orchestrator` ([ADR-0004](../adr/ADR-0004-banco-de-dados-por-microsservico.md)). Vale para todos os fluxos (`TD_*.md`); os TDs descrevem o caminho de negócio e este documento descreve o que acontece quando um passo falha.

## 1. Classificação de erros

| Classe | Definição | Exemplos | Tratamento |
|---|---|---|---|
| **Transitório** | Pode ter sucesso se repetido sem nenhuma correção | Banco indisponível, timeout de conexão, deadlock (`SQLState 40P01`), falha de serialização (`40001`), `lock_timeout` excedido, conflito de controle otimista (`OptimisticLockingFailureException`), HTTP 5xx/429/timeout do Mercado Pago; no MongoDB (orquestrador): transação abortada por conflito (`TransientTransactionError`, `WriteConflict`), troca de primário no replica set (`NotWritablePrimary`, `UnknownTransactionCommitResult`) | Retry com backoff (seção 2) |
| **Permanente** | Vai falhar em todas as tentativas até alguém corrigir algo | Payload inválido / erro de desserialização, validação de contrato, violação de regra de negócio (`BusinessRuleException`, ex.: `RESERVED` insuficiente no `restoreStock`), transição de estado inválida, HTTP 400/401/403/404 do Mercado Pago | Sem retry: direto para a DLQ (seção 3) |
| **Não é erro** | Mensagem esperada e corretamente ignorada | Duplicata detectada em `inbox_events` (no orquestrador, na `transaction_history`); resposta descartada pela guarda de passo no orquestrador (`currentStep` diferente do esperado) | `DeleteMessage` sem efeito + log `INFO`. Não vai para a DLQ |

## 2. Retry de consumidores SQS

Mensageria: filas SQS FIFO com `MessageGroupId = orderId` e DLQ nativa por *redrive policy* ([`infrastructure.md`](../infrastructure/infrastructure.md), seção Mensageria). Consumo com `@SqsListener` (Spring Cloud AWS) em modo de confirmação **manual**.

### 2.1 Estratégia: retry **bloqueante por ordem de serviço**

**Decisão:** todo retry de consumidor é bloqueante dentro do grupo da mensagem. Em erro transitório a mensagem **não é apagada**; enquanto ela está invisível aguardando nova tentativa, a fila FIFO não entrega as mensagens seguintes do mesmo `orderId`. Mensagens de outras ordens seguem normalmente.

**Motivo:** a corretude da saga depende da ordem das mensagens de uma mesma ordem de serviço (`MessageGroupId = orderId`, ver `td_finished_os.md` 0.2). Republicar a mensagem que falhou (no fim da fila ou em outra fila de retry) permitiria que uma mensagem posterior da mesma ordem fosse processada antes. Exemplo: `clearStockPending` falha e é republicada; `sendApproval` é processado antes e é rejeitado pelo bloqueio de pendência. Retry por republicação **não deve ser usado** em nenhum consumidor deste projeto.

**Custo aceito:** enquanto uma mensagem está em retry, as demais mensagens **da mesma ordem** aguardam até o fim do ciclo (máx. ~23 s, seção 2.2).

### 2.2 Parâmetros

| Parâmetro | Valor | Observação |
|---|---|---|
| Backoff | Exponencial: **1 s**, **2 s**, **4 s**, **8 s**, **8 s** | Em erro transitório, a mensagem não é apagada e o *visibility timeout* é alterado para o intervalo correspondente a `ApproximateReceiveCount` (`Visibility.changeTo(...)`) |
| Tentativas | **1 original + 5 retries** | `maxReceiveCount = 6` na *redrive policy*: no 7º recebimento o SQS move a mensagem sozinho para a DLQ (~23 s) |
| Erros permanentes | Não retentados | Enviados pelo próprio consumidor à DLQ (seção 3) e apagados da fila de origem |
| *Visibility timeout* padrão | **30 s** | Maior que o tempo de processamento de uma mensagem |
| *Long polling* | `WaitTimeSeconds = 20` | — |
| Confirmação | `acknowledgementMode = MANUAL` | `DeleteMessage` somente após o `COMMIT` da transação de banco (no orquestrador, após o `commitTransaction`) |
| Concorrência | Processamento sequencial por `MessageGroupId` (garantido pela FIFO) | Grupos diferentes podem ser processados em paralelo |
| Desserialização | Falha ao converter o JSON ou validar o contrato = erro permanente | Vai para a DLQ em vez de voltar à fila em loop |
| `lock_timeout` (PostgreSQL) | `SET LOCAL lock_timeout = '5s'` em toda transação de consumidor | Espera por lock vira erro transitório retentável, em vez de bloquear indefinidamente |
| Transação (MongoDB, orquestrador) | `writeConcern: majority`, `readConcern: snapshot`; duração máxima padrão de 60 s (`transactionLifetimeLimitSeconds`) | Conflito de escrita aborta a transação na hora (não espera lock) e é erro transitório retentável |

### 2.3 Comportamento por tentativa

1. Cada tentativa executa a transação de banco inteira (deduplicação → regra → `inbox_events` → `outbox_events`; no orquestrador, `transaction_history` no lugar de `inbox_events`). Falha → `ROLLBACK` (no orquestrador, `abortTransaction`), nada fica gravado parcialmente.
2. A idempotência (`inbox_events`) garante que uma tentativa que chegou a commitar, mas falhou antes do `DeleteMessage`, não reaplique o efeito.
3. Erro transitório: a mensagem não é apagada e volta a ficar visível após o backoff. Esgotados os 6 recebimentos, o SQS a move para a DLQ.
4. Erro permanente: o consumidor envia a mensagem à DLQ (seção 3) e só então a apaga da fila de origem; o grupo segue.

## 3. DLQ (Dead Letter Queue)

Cada fila tem uma DLQ **nativa** do SQS, também FIFO, ligada por *redrive policy* e provisionada pelo `mechanical-hub-infra`.

| Item | Definição |
|---|---|
| Nome | `<fila de origem>-dlq.fifo` — `mechanical-hub-orders-commands-dlq.fifo`, `mechanical-hub-stock-commands-dlq.fifo`, `mechanical-hub-billing-commands-dlq.fifo`, `mechanical-hub-orchestrator-events-dlq.fifo` |
| Entrada por erro transitório esgotado | Automática: `maxReceiveCount = 6` |
| Entrada por erro permanente | Explícita: o consumidor envia à DLQ com `SqsTemplate` (mesmo corpo, `MessageGroupId = orderId`, `MessageDeduplicationId = eventId`) e depois apaga a mensagem original |
| Atributos | Os *message attributes* originais (contexto de trace) são mantidos; em erro permanente o consumidor acrescenta `errorClass` e `errorMessage`. Respeitar o limite de 10 atributos por mensagem |
| Retenção | **14 dias** |
| Criação | Junto com as filas de origem, no Terraform do `mechanical-hub-infra` (`redrive_allow_policy` restrita à fila de origem) |

### 3.1 Efeito na saga

- A saga da ordem afetada fica parada no passo atual (`currentStep` não avança).
- Mensagens posteriores da mesma ordem que chegarem são descartadas pela guarda de passo (classe "não é erro"); outras ordens seguem normalmente.

### 3.2 Alerta

- Métrica: profundidade das DLQs no CloudWatch (`ApproximateNumberOfMessagesVisible`), lida no Grafana pela fonte de dados CloudWatch, integrada à stack de observabilidade da Fase 3. Opcionalmente, contador por serviço das mensagens enviadas à DLQ por erro permanente (`sqs_dlq_sent_total{queue, reason}`).
- Alerta: **qualquer** mensagem em DLQ (`> 0` em 5 min).

### 3.3 Reprocessamento

Procedimento manual (runbook):
1. Identificar a mensagem na DLQ (console do SQS ou `ReceiveMessage`) pelo corpo (`orderId`, `eventId`) e pelos atributos de erro.
2. Corrigir a causa (deploy de correção, ajuste de dado).
3. Usar o **redrive nativo** da DLQ para a fila de origem (console ou `StartMessageMoveTask`).
4. A idempotência dos consumidores torna o reprocessamento seguro mesmo que parte do efeito já tenha sido aplicada.

Prazo para reprocessar: dentro da retenção da DLQ (14 dias).

## 4. Outbox relay (publicação)

O outbox **não tem DLQ**: uma mensagem gravada em `outbox_events` nunca é descartada; fica `PENDING` até ser publicada.

| Parâmetro | Valor |
|---|---|
| Intervalo de polling | `fixedDelay` **1 s** |
| Lote | **100** por ciclo. PostgreSQL: `ORDER BY sequence`. Orquestrador (MongoDB): por `createdAt`, respeitando `seq` dentro de cada `orderId` (`td_finished_os.md` 0.2) |
| Liderança | PostgreSQL: `pg_try_advisory_lock` fixo por serviço. Orquestrador: lock com expiração (*lease*) em coleção de locks (ex.: ShedLock com provider Mongo). Só a instância líder publica |
| Falha ao publicar uma linha | Interrompe o lote (não pula a linha, para preservar a ordem); `UPDATE outbox_events SET attempts = attempts + 1, last_error = :erro` (no orquestrador, `updateOne` com `$inc: { attempts: 1 }` e `$set: { lastError }`); tenta de novo no próximo ciclo |
| Envio | `SqsTemplate` para a fila FIFO de destino com `MessageGroupId = orderId` e `MessageDeduplicationId = eventId` (a FIFO descarta reenvios iguais em até 5 min); envio síncrono, um por vez, avançando só após a confirmação do SQS |
| Alerta | Linha `PENDING` mais antiga com mais de **60 s** (`outbox_oldest_pending_age_seconds > 60`) |

## 5. Chamadas HTTP ao Mercado Pago (`billing`)

### 5.1 Criação, consulta e cancelamento de order — Orders API (saída)

Endpoints: `POST /v1/orders` (criação, passo 14), `GET /v1/orders/{id}` (webhook e job de expiração), `POST /v1/orders/{id}/cancel` (job de expiração). Detalhes em [`mp_payment_api_integration.md`](integrations/mp_payment_api_integration.md).

| Parâmetro | Valor |
|---|---|
| Timeout de conexão | **2 s** |
| Timeout de leitura | **5 s** |
| Retry no cliente HTTP | **Nenhum** — o retry é feito pelo consumidor SQS (seção 2), para não multiplicar tentativas (retry dentro de retry) |
| Classificação | Timeout, 5xx, 429 → transitório; 400, 401, 403, 404 → permanente |

A chamada é feita fora da transação de banco (`td_finished_os.md` passo 14); retentativas reutilizam a order já gravada em `payments` quando existir, e o header `X-Idempotency-Key` faz o Mercado Pago devolver a mesma order em vez de criar outra.

Job de expiração (`td_payment_failed.md` F2): não usa o retry do consumidor SQS. Erros transitórios (timeout, `5xx`, `429`) → nada a fazer, nova tentativa no próximo ciclo (5 min). Erros permanentes (`401`, `404`) → log de erro e alerta (credencial ou `mp_order_id` inválido).

### 5.2 Webhook (entrada)

| Situação | Resposta | Efeito |
|---|---|---|
| Assinatura inválida | `401` | Mercado Pago não deve reenviar; log de segurança |
| Falha transitória (banco, consulta `GET /v1/orders/{id}` ao MP) | `500` | O Mercado Pago reenvia a notificação (retry do lado do MP) |
| Processado com sucesso ou notificação repetida | `200` | — |

## 6. Endpoints REST

| Situação | Resposta | Responsável pelo retry |
|---|---|---|
| Transição de estado inválida | `409 Conflict` | Não se aplica |
| Conflito de controle otimista (`version`) | `409 Conflict` | Cliente (recarrega e decide) |
| Falha transitória de banco | `503 Service Unavailable` | Cliente |
| `POST /stock/entry` repetido | Resposta armazenada em `idempotency_requests` | Cliente pode repetir com a mesma `Idempotency-Key` |

Com o outbox, a indisponibilidade do SQS não afeta os endpoints REST: a requisição grava no banco e o evento é publicado quando o broker voltar.

## 7. Retenção das tabelas e coleções de controle

| Tabela / coleção | Limpeza | Regra |
|---|---|---|
| `outbox_events` | — | **Permanente**: os enviados ficam com status `SENT` (trilha do que cada serviço publicou) |
| `inbox_events` (orders, stock, billing) | Job diário | Apaga registros com mais de **30 dias** (maior que a retenção da DLQ, para que reprocessamentos ainda sejam deduplicados) |
| `idempotency_requests` | Job diário | Apaga registros com mais de **7 dias** |
| `transaction_history` (orquestrador) | — | Permanente (trilha de auditoria da saga) |

## 8. Pontos de falha por fluxo

| Fluxo / passo | Falha relevante | Classe | Tratamento |
|---|---|---|---|
| `td_finished_os` 7, `td_stock_pending` P1/P4, `td_budget_refused` R3 | Deadlock / `lock_timeout` entre reservas e entradas concorrentes | Transitório | Retry (seção 2); REST → `503` |
| `td_finished_os` 14 | Mercado Pago indisponível ao criar a order (`POST /v1/orders`) | Transitório | Retry do consumidor; esgotado → DLQ e alerta |
| `td_finished_os` 14 | Credencial do Mercado Pago inválida (401) | Permanente | DLQ e alerta |
| `td_finished_os` 17 | Falha ao consultar a order no webhook | Transitório | `500`; Mercado Pago reenvia |
| `td_payment_failed` F2 | Mercado Pago indisponível ao cancelar ou consultar a order | Transitório | Nada a fazer; próximo ciclo do job |
| `td_payment_failed` F2 | Credencial inválida ou `mp_order_id` inexistente (`401`/`404`) | Permanente | Log de erro e alerta |
| Orquestrador (qualquer passo) | Conflito de escrita ou troca de primário no MongoDB | Transitório | Retry (seção 2) |
| `td_finished_os` 22, `td_budget_refused` R3 | `RESERVED` menor que a quantidade a baixar/devolver | Permanente | DLQ e alerta (inconsistência de dados) |
| Qualquer consumidor | Payload fora do contrato | Permanente | DLQ e alerta |
| Qualquer produtor | SQS indisponível | — | Outbox mantém `PENDING`; alerta após 60 s |
