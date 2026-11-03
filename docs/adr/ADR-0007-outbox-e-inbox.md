# ADR-0007: Outbox Permanente e `inbox_events`

## Status
Aceito

## Contexto
Na saga, um serviço altera o próprio banco e publica uma mensagem. Sem cuidado, uma das duas operações pode acontecer sem a outra: o dado muda e o evento se perde, ou o evento sai sem o dado ter mudado. Além disso, a mensageria entrega *at-least-once*, e o orquestrador pode reenviar comandos. Por isso a mesma mensagem pode chegar mais de uma vez.

## Decisão
- **Transactional outbox em todos os serviços:** a alteração de domínio e a mensagem são gravadas em `outbox_events` na mesma transação. Um relay publica as mensagens pendentes, em ordem, e cada uma recebe um `eventId` estável na gravação.
- **Outbox permanente:** as mensagens enviadas ficam com status `SENT` e não são apagadas. Elas são a trilha do que cada serviço publicou.
- **`inbox_events` nos serviços SQL (`orders`, `stock`, `billing`):** registra só as mensagens **processadas com sucesso**, com a chave `eventId` (eventos) ou `idempotencyKey = {transactionId}:{STEP}` (comandos), gravada na mesma transação do efeito. Mensagem já registrada é confirmada sem efeito.
- **No orquestrador (MongoDB):** a `transaction_history`, com índice único em `messageKey`, faz o papel da inbox. O outbox é uma coleção separada, gravada na mesma transação multi-documento.

## Alternativas Consideradas
- **Publicar direto após o commit:** simples, mas perde o evento se o serviço cair entre o commit e a publicação.
- **Outbox com exclusão após o envio:** tabela menor, mas perde a trilha de auditoria. No volume estimado (~1.750 mensagens por dia), crescer não é problema.
- **Inbox registrando também as falhas:** daria mais informação, mas misturaria deduplicação com diagnóstico. As falhas já ficam na DLQ e nos logs.
- **Confiar só na deduplicação do SQS FIFO:** ela vale só por 5 minutos e não cobre o reenvio de um comando pelo orquestrador.

## Consequências
- Todo consumidor executa numa única transação: deduplicação, regra de negócio, `inbox_events` e `outbox_events`. Se falhar, nada fica gravado.
- Há um relay de outbox por serviço, com lock de liderança (advisory lock no PostgreSQL, lease no MongoDB) e alerta quando uma mensagem fica pendente por mais de 60 s.
- A `inbox_events` é limpa por um job diário (registros com mais de 30 dias). O outbox não tem limpeza.
