# Integração com o Mercado Pago — Checkout Pro via Orders API

> Guia de integração de `mechanical-hub-billing` com o Mercado Pago. O fluxo da saga (quem envia qual mensagem) está em [`td_finished_os.md`](../distributedTransaction/td_finished_os.md) (passos 13–18); o tratamento de falhas HTTP está em [`failure_handling.md`](../failure_handling.md) (seção 5). Este documento cobre apenas a integração com a API.

## 1. Decisões

| Decisão | Valor |
|---|---|
| Produto | **Checkout Pro**: o cliente paga na página do Mercado Pago (cartão, Pix); nenhuma tela de pagamento própria. |
| API | **Orders API** (`/v1/orders`), não a Preferences API. Motivo: o estado é consultado por **order** (um objeto por cobrança), e não por uma lista de *payments*, o que simplifica o controle interno do `billing`. |
| Expiração da order | `expiration_time` = criação + **24h05min** (folga de 5 min). |
| Meios de pagamento | **Boleto excluído** (`not_allowed_types: ["ticket"]`), para que nenhum pagamento pendente sobreviva ao prazo do link. |
| Order paga encontrada pelo job de expiração | O job **não** declara falha: segue com a ordem e publica `paymentConfirmed`. |

O `billing` **não executa o pagamento**: ele cria a cobrança (order), entrega o `checkout_url` e descobre o resultado (webhook + consulta). Cartão, Pix e antifraude ficam com o Mercado Pago.

## 2. Configuração (uma vez, sem código)

1. **Aplicação:** em *Suas integrações* (painel Mercado Pago Developers), criar uma aplicação do tipo Checkout Pro.
2. **Credenciais:** copiar o *Access Token* de teste (e, depois, o de produção). Armazenar como secret do Kubernetes (`MP_ACCESS_TOKEN`); nunca no repositório.
3. **Webhook:** em *Suas integrações → aplicação → Webhooks → Configurar notificações*:
   - URL pública do `billing`: rota do API Gateway → `POST /webhooks/payment`;
   - evento: **Order (Mercado Pago)**;
   - copiar a **chave secreta** e armazenar como secret (`MP_WEBHOOK_SECRET`) — usada para validar `x-signature`.
   - A Orders API não recebe `notification_url` na criação da order: a URL vem desta configuração.
4. **Usuários de teste:** criar um vendedor e um comprador de teste para simular pagamentos.

Toda chamada usa:
```
Base URL:      https://api.mercadopago.com
Authorization: Bearer {MP_ACCESS_TOKEN}
```

## 3. Fluxo por ordem

```
Orquestrador ──generatePaymentLink──▶ Billing
                                       │ (A) POST /v1/orders ─────────▶ Mercado Pago
                                       │ ◀── { id, status: created, checkout_url }
                                       │ grava em payments
Orquestrador ◀──paymentLinkGenerated── Billing
Orquestrador ──sendPaymentLink──▶ Orders ──notifica──▶ Cliente

Cliente ──(B) abre checkout_url e paga──▶ Mercado Pago

Mercado Pago ──(C) POST /webhooks/payment──▶ Billing
                                       │ valida x-signature
                                       │ (D) GET /v1/orders/{id} ──▶ Mercado Pago
                                       │ ◀── { status: processed, status_detail: accredited }
Orquestrador ◀──paymentConfirmed────── Billing
```

### (A) Criar a order — `td_finished_os` passo 14

```http
POST https://api.mercadopago.com/v1/orders
Authorization: Bearer {MP_ACCESS_TOKEN}
X-Idempotency-Key: {transactionId}:GENERATE_PAYMENT_LINK
Content-Type: application/json

{
  "type": "online",
  "processing_mode": "manual",
  "total_amount": "350.00",
  "external_reference": "{orderId}",
  "expiration_time": "{criação + 24h05min, ISO 8601}",
  "items": [
    {
      "title": "Ordem de serviço {orderId} - Mechanical Hub",
      "unit_price": "350.00",
      "quantity": 1,
      "total_amount": "350.00"
    }
  ],
  "config": {
    "payment_method": { "not_allowed_types": ["ticket"] }
  }
}
```

- `type: "online"` e `processing_mode: "manual"` são obrigatórios para o Checkout Pro ("manual" = nada é cobrado agora; a cobrança acontece quando o cliente paga).
- `total_amount` deve ser igual à soma de `items[].total_amount`. Valores são **strings**.
- `X-Idempotency-Key`: se o consumidor SQS reprocessar o comando, o Mercado Pago devolve a mesma order em vez de criar outra.
- `external_reference` (até 64 caracteres) liga a order do MP à ordem de serviço.
- `payer` é opcional e **não é enviado**: o `billing` não guarda dados do cliente, e o cliente informa seus dados na própria tela do Checkout Pro.
- Opcional: `config.online.success_url` / `failure_url` / `pending_url` — página para onde o cliente volta após pagar. É só navegação; **não** confirma pagamento.

Resposta (`201`):
```json
{
  "id": "ORDTST01KS5AJ6HTK2HRQ3XJ3C2JCKP9",
  "status": "created",
  "checkout_url": "https://www.mercadopago.com.br/checkout/v1/redirect?order_id=ORDTST01...",
  "total_amount": "350.00",
  "currency": "BRL"
}
```
- `id` → gravar em `payments.mp_order_id`: chave para consultar, cancelar e reembolsar. Prefixo `ORDTST` = sandbox.
- `checkout_url` → é o **link de pagamento** enviado ao cliente (`paymentLinkGenerated` → `sendPaymentLink`).

### (B) Cliente paga

O cliente abre o `checkout_url` e paga com cartão ou Pix. Se o cartão for recusado, ele pode tentar novamente na mesma order enquanto ela não expirar (até o limite do MP — `status_detail: max_attempts_exceeded`).

### (C) Webhook — `td_finished_os` passo 17

```
POST /webhooks/payment            (Mercado Pago → billing)
x-signature: ts={timestamp},v1={hmac_sha256}
x-request-id: {id}
```
```json
{
  "action": "order.processed",
  "type": "order",
  "api_version": "v1",
  "data": { "id": "ORD...", "status": "processed", "status_detail": "accredited" }
}
```
1. Validar `x-signature` com `MP_WEBHOOK_SECRET` (HMAC SHA-256; o SDK oferece validador). Inválida → `401`.
2. Não confiar apenas no corpo: seguir para (D).
3. Responder `200`/`201` em até **22 s**. Sem sucesso, o MP reenvia (≈15 min, 30 min, 6 h e depois em intervalos maiores).

A única ação documentada com exemplo é `order.processed`. **Não há notificação documentada para order expirada ou recusada** — por isso existe o job de expiração (seção 5).

### (D) Consultar a order

```http
GET https://api.mercadopago.com/v1/orders/{mp_order_id}
Authorization: Bearer {MP_ACCESS_TOKEN}
```
Se `status = processed` e `status_detail = accredited`: atualizar `payments` e publicar `paymentConfirmed` (outbox). Notificação repetida com `payments` já aprovado → nada a fazer, `200`.

## 4. Status da order no Mercado Pago

| `status` | `status_detail` (exemplos) | Significado para o billing |
|---|---|---|
| `created` | — | Aguardando o cliente. |
| `processing` | `in_process`, `pending_review_manual` | Pagamento em análise; ainda não confirmado. |
| `processed` | `accredited` | **Pago** → `paymentConfirmed`. |
| `processed` | `refunded`, `partially_refunded` | Reembolsado. |
| `action_required` | `waiting_capture` | Autorizado e não capturado (não esperado no fluxo do Checkout Pro). |
| `failed` | `rejected_by_issuer`, `card_insufficient_amount`, `high_risk`, `max_attempts_exceeded`… | Tentativa recusada. |
| `canceled` | `canceled_transaction` | Cancelada ou expirada sem pagamento. |

## 5. Operações usadas no cenário de falha (`td_payment_failed.md`)

| Operação | Endpoint | Observações |
|---|---|---|
| Consultar | `GET /v1/orders/{id}` | Usada pelo job antes de declarar falha. Order `processed` → segue com a ordem e publica `paymentConfirmed`. |
| Cancelar | `POST /v1/orders/{id}/cancel` + `X-Idempotency-Key` | Só para `created` ou `action_required`; `409 order_already_canceled` caso contrário. Após o cancelamento, nenhum novo pagamento pode ser feito na order. |
| Reembolsar | endpoint de reembolso da order | Para pagamento confirmado que precise ser devolvido. |

A ordem entre cancelar a order no MP e publicar o evento de falha na saga é definida em `td_payment_failed.md`.

## 6. Implementação no `billing` (Kotlin / Java 25 / Spring Boot 4.1)

- **Porta** (camada `application`): `PaymentGateway` com `createOrder(...)`, `getOrder(mpOrderId)`, `cancelOrder(mpOrderId)` — o domínio não conhece o Mercado Pago.
- **Adapter** (camada `infrastructure`): implementa a porta com o SDK oficial (`com.mercadopago:sdk-java`) ou cliente HTTP; lê `MP_ACCESS_TOKEN` do ambiente.
- **Controller do webhook**: valida assinatura, consulta a order via porta, grava e publica pelo outbox.
- Timeouts e classificação de erros: `failure_handling.md` seção 5 (sem retry no cliente HTTP; retry pelo consumidor SQS).
- Em ambiente local, o webhook só é alcançável por túnel ou pela URL pública do API Gateway.

## 7. Testes (sandbox)

1. Criar a order com o Access Token de **teste**.
2. Abrir o `checkout_url` em **janela anônima**, logado com o comprador de teste.
3. Pagar com cartão de teste; o **nome do titular** define o resultado:

| Titular | Resultado |
|---|---|
| `APRO` | Aprovado |
| `OTHE` | Recusado (erro geral) |
| `FUND` | Recusado (saldo insuficiente) |
| `SECU` | Recusado (código de segurança) |
| `CONT` | Pendente |

4. Para testar expiração, criar uma order com `expiration_time` curto e não pagar.

## 8. Referências

- [Mercado Pago Developers](https://www.mercadopago.com.br/developers/pt)
- [API Reference](https://www.mercadopago.com.br/developers/pt/reference)
- [Checkout Pro via Orders — visão geral](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-orders/overview)
- [Criar e configurar uma order](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-orders/create-order)
- [Create order — API Reference](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro/create-order/post)
- [Cancel order — API Reference](https://www.mercadopago.com.co/developers/en/reference/online-payments/checkout-pro/cancel-order/post)
- [Configurar notificações](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-orders/notifications)
- [Status da order](https://www.mercadopago.com.mx/developers/en/docs/checkout-pro-orders/payment-management/status/order-status)
- [Compras de teste](https://www.mercadopago.com.mx/developers/pt/docs/checkout-pro-preferences/integration-test/test-purchases)
