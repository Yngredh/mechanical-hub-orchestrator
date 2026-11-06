# ADR-0005: Divisão em Quatro Microsserviços

## Status
Aceito

## Contexto
Na Fase 4, o monolito `mechanical-hub` dá lugar a microsserviços. O desafio pede separar ao menos ordens de serviço, pagamento e execução, cada um com banco próprio, e coordenar o fluxo com Saga. A divisão precisava seguir os limites do domínio e quem é dono de cada dado, e não as camadas técnicas.

## Decisão

| Serviço | Responsabilidade | Dono de |
|---|---|---|
| `mechanical-hub-orders` | Ponto de contato com mecânicos e cliente (API REST), ciclo de vida e status da ordem, notificação ao cliente | Clientes, veículos, funcionários, ordens e reparos |
| `mechanical-hub-stock` | Reserva, devolução e baixa de materiais, pendências de estoque | Relação reparo↔material e quantidades. Não conhece preço |
| `mechanical-hub-billing` | Orçamento e pagamento (Mercado Pago) | Preços de mão de obra e de materiais, orçamentos e pagamentos |
| `mechanical-hub-orchestrator` | Coordenação da Saga e compensações | Estado e histórico de cada saga |

- Cada serviço tem repositório, pipeline, deploy e banco próprios. Nenhum serviço acessa o banco de outro.
- O nome `stock`, e não `inventory`, segue o vocabulário que o sistema já usava.
- A notificação ao cliente fica no `orders`, via adapter, e não vira serviço próprio por ora.
- O catálogo de reparos, materiais e preços é fixo, carregado por migrations de cada serviço com os mesmos ids. O cadastro administrativo ficou fora do escopo (FR-08).

## Alternativas Consideradas
- **Orquestrador embutido no `orders`:** menos um serviço, mas misturaria a regra de negócio da ordem com a coordenação do fluxo e acoplaria a escala dos dois.
- **Serviço de notificação separado:** adiado. O volume e o escopo atuais não justificam mais um deploy.
- **Divisão mais fina (clientes, veículos, catálogo):** descartada. Esses dados são relacionados entre si e muito consultados juntos, então separá-los traria chamadas entre serviços sem ganho.

## Consequências
- O fluxo da ordem vira uma transação distribuída, com consistência eventual entre os serviços ([RFC-0005](../rfc/RFC-0005-saga-orquestrada.md)).
- Os dados que dois serviços precisam conhecer circulam nas mensagens da saga, como os ids de reparo e material.
- A Lambda `authenticate` continua lendo `users`/`profiles` direto no banco do `orders`. É uma exceção consciente, tratada como parte do domínio de `orders`.
- Quatro repositórios novos de aplicação, e o monolito fica só como referência.
