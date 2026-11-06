# RFC-0007: Integração com o Mercado Pago

## Status
Aceito. Os detalhes de implementação estão em [`mp_payment_api_integration.md`](../design/integrations/mp_payment_api_integration.md).

## Contexto
O `mechanical-hub-billing` precisa cobrar o cliente depois que ele aprova o orçamento. O desafio exige integração com o Mercado Pago, mas deixa livre a escolha do produto e da API. O cliente não se autentica no sistema: ele recebe um link, aprova o orçamento e paga.

## Proposta
Usar o **Checkout Pro pela Orders API** (`POST /v1/orders`).

- O `billing` cria a order e entrega o `checkout_url` para o cliente. O pagamento (cartão, Pix) e o antifraude ficam com o Mercado Pago.
- A confirmação chega por webhook (`POST /webhooks/payment`), com validação da assinatura e consulta à order (`GET /v1/orders/{id}`) antes de publicar `paymentConfirmed`.
- O link expira em 24h. Um job interno do `billing` detecta o vencimento, cancela a order e publica a falha de pagamento, o que dispara a compensação da saga.
- O boleto fica excluído, para que nenhum pagamento pendente sobreviva ao prazo do link.

## Justificativa
- **Sem tela de pagamento própria:** menos código e nada de dado de cartão passando pelo sistema.
- **Orders API:** o estado é consultado por um único objeto por cobrança (a order), e não por uma lista de *payments*. Isso simplifica o controle interno do `billing`.
- **Webhook com consulta de confirmação:** o resultado é rastreável e a notificação não precisa ser tratada como fonte única de verdade.

## Alternativas Consideradas
- **Checkout Pro pela Preferences API:** funciona, mas o resultado vem como vários *payments* por preferência, e o `billing` teria que consolidar o estado.
- **Checkout API / Checkout Bricks:** exigiriam uma interface de pagamento própria, desnecessária para o cenário.
- **Link de Pagamento:** é criado manualmente no painel, sem API para automatizar.

## Consequências
- O `billing` precisa de uma rota pública no API Gateway para o webhook e dos secrets `MP_ACCESS_TOKEN` e `MP_WEBHOOK_SECRET`.
- A corrida entre o webhook e o job de expiração é resolvida com lock de linha no pagamento. Se o job encontrar a order já paga, ele confirma o pagamento em vez de declarar falha.
- A criação da order usa `X-Idempotency-Key` para que um reenvio do comando não gere duas cobranças.
