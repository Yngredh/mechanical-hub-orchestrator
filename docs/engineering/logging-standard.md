# Padrão de Logs

Regras de log para os serviços `billing`, `stock`, `orders` e `orchestrator`. Objetivo: filtrando os logs de todos os serviços por `orderId`, ver cada etapa por que a ordem passou, o que cada serviço fez, quantas vezes tentou e onde falhou, tanto lendo quanto consultando.

Complementa [`development-standards.md`](development-standards.md). Stack de observabilidade: logs no Loki, traces no Tempo, ambos no Grafana.

## 1. Formato

Todos os serviços logam com **SLF4J**, usando placeholders `{}` numa mensagem no formato `chave=valor` (logfmt):

```kotlin
log.info("orderId={} status={} stage={} msg=\"{}\"", orderId, "GENERATE_BUDGET", "END", "Orçamento da ordem gerado")
```

```
orderId=7f3c9a2e-… status=GENERATE_BUDGET stage=END msg="Orçamento da ordem gerado"
```

O encoder (`LogstashEncoder`) envia cada linha em **JSON** para o stdout. A mensagem acima vai no campo `message`, e as queries a interpretam com `| logfmt` (§7).

Regras da mensagem:
- Ordem das chaves: primeiro os campos obrigatórios `orderId status stage attempt durationMs`, depois os campos de contexto (§2.1) e por fim `msg ex exMsg`. Uma chave que não se aplica é omitida.
- `msg` e `exMsg` entre aspas (`msg=\"{}\"`), porque têm espaços. Os demais valores não têm espaço e vão sem aspas.
- `msg` e `exMsg` não podem conter aspas duplas, que quebrariam o logfmt.

Ficam **só no JSON**, fora da mensagem:
- `service`, fixo por serviço (§8);
- `orderId` e `eventId`, colocados pelo MDC (§8);
- `trace_id` e `span_id` (agente OpenTelemetry);
- `logger_name` (classe) e `stack_trace`.

## 2. Campos

| Campo | Obrigatório | Descrição |
|---|---|---|
| `orderId` | Sim, em todo log da transação | Chave para ver o fluxo completo da ordem em todos os serviços. |
| `status` | Sim, em log de etapa | O que o serviço está fazendo. Vocabulário compartilhado entre os serviços (§3). |
| `stage` | Sim, em log de etapa | `START`, `END` ou `FAIL`: em que ponto da etapa o serviço está. |
| `msg` | Sim | Descrição curta e legível do que está acontecendo, em português. |
| `attempt` | Ao consumir mensagem | Número da tentativa (`ApproximateReceiveCount` do SQS). |
| `durationMs` | Em `stage=END` e `stage=FAIL` | Duração da etapa em milissegundos, desde o `START`. |
| `ex` | Em `stage=FAIL` | Nome simples da classe da exceção. |
| `exMsg` | Em `stage=FAIL` | Mensagem da exceção, sem dados sensíveis (§6). |
| `service` | Sim, só no JSON | Nome do serviço (`mechanical-hub-billing`…), fixo no `logback-spring.xml`. |
| `eventId` | Ao consumir ou publicar mensagem, só no JSON | Id da mensagem que causou o log, colocado pelo MDC. |

### 2.1 Campos de contexto

Os campos acima são o **mínimo obrigatório** em todos os serviços. Além deles, cada log pode trazer variáveis úteis para depuração, no mesmo formato `variableName={}`:

```
orderId=7f3c9a2e-… status=RESERVE_STOCK stage=END durationMs=38 materialId=99370d9f-… requestedQty=2 availableQty=0 msg="Material insuficiente, ordem marcada como pendente"
orderId=7f3c9a2e-… status=GENERATE_BUDGET stage=END durationMs=42 budgetId=b51e7a03-… orderCost=487.40 msg="Orçamento da ordem gerado"
```

**Faça**
- Nomes em camelCase, iguais aos do domínio ou dos contratos de mensagem (`budgetId`, `materialId`, `orderCost`, `mpOrderId`).
- O mesmo nome para o mesmo dado em todos os serviços: `materialId` é `materialId` no stock, no orchestrator e no billing.
- Status que **o próprio serviço** possui: `orderStatus` (orders), `sagaStatus` (orchestrator), `reservationStatus` (stock), `budgetStatus`/`paymentStatus` (billing). Nenhum serviço loga status que pertence a outro.
- Listas com valores separados por vírgula e sem espaço: `materialIds=99370d9f-…,a7f744e8-…`.
- Contexto pesado ou frequente em `DEBUG`.

**Não faça**
- Logar objetos inteiros (`budget={}` com o `toString()` de uma data class). Isso traz campos não planejados, possivelmente sensíveis, e quebra o formato. Logue os campos que interessam, um a um.
- Logar como contexto qualquer coisa proibida em §6.
- Reusar o nome de um campo obrigatório com outro significado.

O nome `status` é reservado para o campo do log. No orchestrator, o `status` da saga (`STARTED`, `COMPENSATING`…) sai como `sagaStatus`.

## 3. Vocabulário de `status`

- SCREAMING_SNAKE_CASE, derivado do nome do comando ou da ação: `generatePaymentLink` vira `GENERATE_PAYMENT_LINK`.
- O **mesmo valor** é usado por todos os serviços que participam da etapa. O orchestrator, ao publicar `generateBudget`, e o billing, ao executá-lo, logam `status=GENERATE_BUDGET`. Filtrar por `status` mostra a etapa nos dois lados.
- Valores alinhados aos comandos de [`saga_transaction_status.md`](../design/saga_transaction_status.md): `RESERVE_STOCK`, `MARK_STOCK_PENDING`, `CLEAR_STOCK_PENDING`, `GENERATE_BUDGET`, `SEND_APPROVAL`, `GENERATE_PAYMENT_LINK`, `SEND_PAYMENT_LINK`, `CONFIRM_PAYMENT`, `EXPIRE_PAYMENT`, `RELEASE_ORDER`, `CONSUME_STOCK`, `CANCEL_ORDER`, `RESTORE_STOCK`.
- Ações que não são comandos da saga, como as rotas REST, usam o mesmo formato:
  - orders: `CREATE_ORDER`, `ADD_REPAIRS`, `COMPLETE_DIAGNOSIS` (status `PREPARING`), `START_EXECUTION` (`IN_PROGRESS`), `UPDATE_TASK_STATUS`, `FINISH_ORDER` (`FINISHED`), `DELIVER_ORDER` (`DELIVERED`), `CHANGE_ORDER_STATUS` (status pedido fora dessa lista, sempre rejeitado), `APPROVE_ORDER`, `REJECT_ORDER`;
  - stock: `REGISTER_STOCK_ENTRY` (`POST /stock/entry`). A entrada não pertence a uma ordem; cada ordem cuja pendência é atendida ganha as próprias linhas `RESERVE_STOCK`.
- No orchestrator, o consumo de um evento usa o `status` do comando que ele publica em resposta; os eventos que encerram a saga usam o passo que encerram:

  | Evento recebido | `status` | Evento recebido | `status` |
  |---|---|---|---|
  | `diagnosisCompleted` | `RESERVE_STOCK` | `paymentLinkGenerated` | `SEND_PAYMENT_LINK` |
  | `stockPending` | `MARK_STOCK_PENDING` | `paymentConfirmed` | `RELEASE_ORDER` |
  | `stockReserved` | `GENERATE_BUDGET` | `paymentFailed` | `CANCEL_ORDER` |
  | `budgetGenerated` | `SEND_APPROVAL` | `orderCanceled` | `RESTORE_STOCK` |
  | `orderApproved` | `GENERATE_PAYMENT_LINK` | `orderFinished` | `CONSUME_STOCK` |
  | `orderRejected` | `RESTORE_STOCK` | `stockConsumed` / `stockRestored` | `CONSUME_STOCK` / `RESTORE_STOCK` |

- O relay do outbox loga a publicação com o `status` da etapa que gravou o evento (`budgetGenerated` → `GENERATE_BUDGET`, `paymentConfirmed` → `CONFIRM_PAYMENT`, `paymentFailed` → `EXPIRE_PAYMENT`). A publicação com sucesso sai sem `stage` (não entra nas queries de duração); a falha sai com `stage=FAIL`.
- Um valor novo entra nesta lista antes de ser usado no código.

## 4. Quando logar

Toda etapa gera no mínimo um par de logs: `START` ao começar, e `END` ou `FAIL` ao terminar.

| Ponto | Exemplo de `msg` |
|---|---|
| Entrada HTTP | `"Aprovação do cliente recebida"` |
| Consumo de mensagem SQS, antes do caso de uso | `"Iniciando geração de orçamento"` |
| Chamada a sistema externo (Mercado Pago) | `"Criando order no Mercado Pago"` |
| Mudança de estado de domínio | `"Orçamento da ordem gerado"` |
| Gravação de evento no outbox | `"Evento budgetGenerated gravado no outbox"` |
| Envio pelo relay do outbox | `"Evento budgetGenerated publicado"` |
| Compensação, expiração, envio para DLQ | `"Comando enviado para a DLQ"` |

**Retries:** cada tentativa loga de novo a sequência `START` → `FAIL`/`END` com o `attempt` atualizado. A repetição é intencional: mostra que houve retry, quantas vezes e com qual erro.

## 5. Níveis

| Nível | Uso |
|---|---|
| `INFO` | `START` e `END` das etapas. |
| `WARN` | `FAIL` transitório que vai ser tentado de novo. |
| `ERROR` | `FAIL` permanente: DLQ, compensação por erro, falha que exige intervenção. A exceção é passada como último argumento para gerar o `stack_trace`. |
| `DEBUG` | Payloads e detalhes de investigação. Desligado em produção. |

## 6. Dados sensíveis

**Nenhum log expõe informação sensível do cliente nem variável do sistema.** Isso vale para `msg`, `exMsg`, payloads em `DEBUG` e `stack_trace`.

**Nunca logar**
- Dados pessoais do cliente: nome, e-mail, telefone, documento, endereço, placa associada a pessoa.
- Credenciais e segredos: tokens (`MP_TOKEN`, JWT), senhas, `x-signature` e secret do webhook, connection strings, valores de variáveis de ambiente.
- Payload completo de requisição ou mensagem em `INFO`/`WARN`/`ERROR`.

**Pode logar**
- Ids técnicos (`orderId`, `eventId`, `budgetId`, `mpOrderId`) e status.
- O **nome** do que está errado, sem o valor: `exMsg="MP_TOKEN inválido"` pode; o conteúdo do token, não.

Exceções de bibliotecas externas podem trazer valores na mensagem. Ao logar `exMsg` de exceção de terceiros, prefira uma mensagem própria.

## 7. Regras para queries

- Queries, alertas e dashboards usam campos estruturados: os obrigatórios (`orderId`, `status`, `stage`, `attempt`, `durationMs`, `ex`), `level`, `service` e, para investigação, os campos de contexto.
- `msg` serve só para leitura humana, pode ser reescrita a qualquer momento e **nunca** entra em query, alerta ou dashboard.
- Ids (`orderId`, `eventId`, `trace_id`) **nunca** viram label do Loki, porque a cardinalidade é alta. Eles são filtrados na consulta.

Toda query começa com o mesmo pipeline: `| json` lê o envelope (nível, classe, `trace_id`), `line_format` troca a linha pela `message` e `| logfmt` extrai os campos `chave=valor`.

```logql
# Fluxo completo de uma ordem (no Grafana: ordenar "Oldest first")
{namespace="mechanical-hub"} | json | line_format "{{.message}}" | logfmt | orderId="<orderId>"

# Todas as falhas de todos os serviços
{namespace="mechanical-hub"} | json | line_format "{{.message}}" | logfmt | stage="FAIL"

# Em quais etapas as falhas acontecem
sum by (service, status) (count_over_time({namespace="mechanical-hub"} | json | line_format "{{.message}}" | logfmt | stage="FAIL" [1h]))

# Causas de falha numa etapa
sum by (ex, exMsg) (count_over_time({namespace="mechanical-hub"} | json | line_format "{{.message}}" | logfmt | status="GENERATE_PAYMENT_LINK" | stage="FAIL" [24h]))

# Ordens que precisaram de retry
{namespace="mechanical-hub"} | json | line_format "{{.message}}" | logfmt | attempt > 1

# Etapas lentas (acima de 2s)
{namespace="mechanical-hub"} | json | line_format "{{.message}}" | logfmt | stage="END" | durationMs > 2000

# p95 da duração por etapa
quantile_over_time(0.95, {namespace="mechanical-hub"} | json | line_format "{{.message}}" | logfmt | stage="END" | unwrap durationMs [1h]) by (service, status)

# Ordens afetadas pela falta de um material (campo de contexto)
{namespace="mechanical-hub"} | json | line_format "{{.message}}" | logfmt | materialId="<materialId>"
```

Logs técnicos sem o formato `chave=valor` (de bibliotecas, por exemplo) não são extraídos pelo `logfmt`. Mesmo assim, aparecem no filtro por ordem, porque o MDC coloca o `orderId` no JSON: `| json | orderId="<orderId>"`.

Ajuste o seletor `{namespace=…}` aos labels gerados pelo `otel-logs-agent`. No Grafana, um *derived field* sobre `trace_id` liga a linha de log ao trace no Tempo.

Uma etapa que começou e nunca terminou, como um pod que caiu no meio, não gera `FAIL`. O log ajuda a investigar esse caso, mas a detecção confiável vem do estado da saga no orchestrator (`currentStep` parado há tempo demais).

## 8. Implementação

**Faça**
- Logger SLF4J por classe: `private val log = LoggerFactory.getLogger(MinhaClasse::class.java)`.
- Declarar o `service` no `logback-spring.xml` de cada serviço:

```xml
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <customFields>{"service":"mechanical-hub-billing"}</customFields>
</encoder>
```

- Medir `durationMs` a partir do instante do `START`, com o `Clock` injetado (`Duration.between(inicio, clock.instant()).toMillis()`).
- Popular o MDC com `orderId` (e `eventId`) assim que a mensagem ou requisição chegar, e limpar no `finally`. Os logs técnicos de bibliotecas também passam a carregar o `orderId`.
- No relay do outbox, repopular o MDC a partir da linha do evento antes de enviar.
- No webhook e nos jobs, colocar o `orderId` no MDC assim que o pagamento for carregado do banco.
- Montar a mensagem com placeholders `{}`. Na falha, a exceção vai como **último argumento, sem placeholder**: o SLF4J a reconhece e o encoder gera o `stack_trace`.

```kotlin
log.info(
    "orderId={} status={} stage={} durationMs={} budgetId={} orderCost={} msg=\"{}\"",
    orderId, "GENERATE_BUDGET", "END", durationMs, budget.id, budget.total, "Orçamento da ordem gerado",
)

log.error(
    "orderId={} status={} stage={} attempt={} durationMs={} budgetId={} msg=\"{}\" ex={} exMsg=\"{}\"",
    orderId, "GENERATE_PAYMENT_LINK", "FAIL", attempt, durationMs, budgetId, "Falha na criação do link de pagamento",
    e.javaClass.simpleName, e.message,
    e,
)
```

**Não faça**
- Logar `class` manualmente: o `logger_name` já identifica a classe.
- Usar string template do Kotlin na mensagem (`"orderId=$orderId"`) em vez de `{}`. O placeholder só monta a string se o nível estiver ativo e mantém o padrão da mensagem fixo.
- Logar a mesma falha em várias camadas. Quem decide o destino da falha (retry, DLQ, resposta HTTP) é quem loga o `FAIL`.

## 9. Exemplo: falha ao gerar o link de pagamento

```
15:40:02.117 INFO  orders        orderId=7f3c9a2e status=APPROVE_ORDER         stage=START msg="Aprovação do cliente recebida"
15:40:02.140 INFO  orders        orderId=7f3c9a2e status=APPROVE_ORDER         stage=END   msg="Ordem aprovada, notificando orquestrador"
15:40:02.981 INFO  orchestrator  orderId=7f3c9a2e status=GENERATE_PAYMENT_LINK stage=START msg="Aprovação recebida, solicitando link de pagamento"
15:40:03.012 INFO  orchestrator  orderId=7f3c9a2e status=GENERATE_PAYMENT_LINK stage=END   msg="Comando generatePaymentLink gravado no outbox"
15:40:03.874 INFO  billing       orderId=7f3c9a2e status=GENERATE_PAYMENT_LINK stage=START attempt=1 msg="Iniciando criação de link de pagamento"
15:40:04.102 WARN  billing       orderId=7f3c9a2e status=GENERATE_PAYMENT_LINK stage=FAIL  attempt=1 msg="Falha transitória ao criar link, será reprocessado" ex=TransientGatewayException exMsg="Mercado Pago respondeu 503"
15:40:34.880 INFO  billing       orderId=7f3c9a2e status=GENERATE_PAYMENT_LINK stage=START attempt=2 msg="Iniciando criação de link de pagamento"
15:40:35.231 ERROR billing       orderId=7f3c9a2e status=GENERATE_PAYMENT_LINK stage=FAIL  attempt=2 msg="Falha na criação do link de pagamento" ex=PermanentGatewayException exMsg="MP_TOKEN inválido"
15:40:35.236 ERROR billing       orderId=7f3c9a2e status=GENERATE_PAYMENT_LINK stage=FAIL  attempt=2 msg="Comando enviado para a DLQ" ex=PermanentCommandException exMsg="MP_TOKEN inválido"
```

## 10. Pendências

- **Mensagens descartadas** (resolvida): saem como `stage=END` com o campo `outcome` (`DUPLICATE`, `DISCARDED_STEP_GUARD`, `REPLAY`, `ALREADY_FINALIZED`) e `msg` explicativa, em `INFO`. Não são erro (failure_handling §1).
- **Distribuição nos repositórios** (resolvida): helper copiado em cada serviço, em `com.fiap.mechanicalhub.<serviço>.logging.FlowLog`. A borda (listener SQS, controller, webhook, job) abre a etapa e loga `START`/`END`/`FAIL`; o caso de uso só acrescenta contexto (`FlowLog.detail`) e a `msg` do `END` (`FlowLog.endMessage`). O helper também garante que o `orderId` do MDC seja o mesmo da mensagem.
- **`traceparent` no outbox:** guardar o contexto de trace na linha do outbox e restaurá-lo no relay. Sem isso, cada trace no Tempo cobre só "ciclo do relay de um serviço + consumo no próximo", e pode misturar ordens do mesmo lote. A navegação pelo fluxo continua sendo pelo `orderId` no Loki.
