# ADR-0006: Comunicação Híbrida — REST na Borda e Mensageria entre Serviços

## Status
Aceito

## Contexto
O sistema tem dois tipos de interação. A primeira é com pessoas e sistemas externos (mecânico, cliente, webhook do Mercado Pago), que esperam uma resposta imediata. A segunda é entre os serviços durante a saga: são passos que podem esperar, precisam sobreviver à indisponibilidade de um serviço e não podem se perder.

## Decisão
- **Síncrona (REST):** interface com mecânicos e cliente, sempre pelo `orders`; webhook do Mercado Pago no `billing`; chamadas do `billing` à API do Mercado Pago; entrada de estoque no `stock`.
- **Assíncrona (Amazon SQS):** toda a comunicação da saga entre o orquestrador e os serviços.
  - O orquestrador envia comandos para uma fila por serviço (`orders`, `stock`, `billing`) e recebe os eventos de resposta numa fila própria.
  - As filas são FIFO com `MessageGroupId = orderId`, para que as mensagens de uma mesma ordem sejam entregues na ordem de publicação. Cada fila tem a sua DLQ.
- Os serviços de domínio não chamam uns aos outros via REST durante a saga.

## Alternativas Consideradas
- **Somente REST entre serviços:** acoplamento temporal. Se o `stock` estiver fora, a saga falha em vez de esperar, e o retry fica todo com quem chama.
- **Somente mensageria:** a interface com o mecânico e o cliente precisa de resposta imediata, e o Mercado Pago só fala HTTP.
- **Kafka como broker:** foi a primeira escolha, depois trocado pelo SQS. Para 4 serviços com comunicação ponto a ponto, o Kafka adicionaria nós, armazenamento e operação no cluster sem ganho. O SQS é gerenciado e está liberado no Lab (detalhes em [`infrastructure.md`](../infrastructure/infrastructure.md)).

## Consequências
- A entrega das mensagens é *at-least-once*, então todo consumidor precisa ser idempotente ([ADR-0007](ADR-0007-outbox-e-inbox.md)).
- O mecânico vê o resultado de passos assíncronos (reserva, orçamento) por consulta ao status da ordem, e não na resposta da requisição.
- Duas formas de tratar falha: retry e DLQ nas filas; timeout e retry controlados nas chamadas HTTP externas ([`failure_handling.md`](../design/failure_handling.md)).
