# RFC-0005: Saga Pattern com Orquestração

## Status
Aceito

## Contexto
Na Fase 4, o fluxo de uma ordem de serviço passa por quatro microsserviços (`orders`, `stock`, `billing` e `orchestrator`), cada um com o seu banco. Não há transação ACID entre eles, então o fluxo precisa de um mecanismo de transação distribuída com compensações (orçamento recusado, pagamento não confirmado em 24h, pendência de estoque). O desafio pede escolher entre Saga orquestrada e coreografada.

## Proposta
Adotar **Saga orquestrada**, com um serviço dedicado (`mechanical-hub-orchestrator`), que tem repositório, deploy e banco próprios.

- Uma saga por ordem: começa no fim do diagnóstico e termina na baixa do estoque, quando a ordem é finalizada.
- O orquestrador persiste o estado da saga, envia comandos aos serviços pela mensageria e dispara as compensações.
- Os serviços de domínio só conhecem o próprio passo. Nenhum deles conhece o fluxo completo.

## Justificativa
- **Visibilidade:** o passo atual de cada ordem fica em um único lugar, o que facilita acompanhar, auditar e depurar o fluxo.
- **Fluxo longo e com desvios:** são muitos passos, com espera por pessoas (aprovação do cliente, pagamento) e vários caminhos de compensação. Centralizar essas regras é mais simples do que espalhá-las em reações a eventos.
- **Menos acoplamento entre os serviços de domínio:** `stock` e `billing` não precisam escutar os eventos uns dos outros.

## Alternativas Consideradas
- **Saga coreografada:** cada serviço reage aos eventos dos outros. Descartada porque o fluxo completo ficaria implícito, distribuído entre os serviços, e as compensações seriam difíceis de rastrear e testar.
- **Transação distribuída síncrona (2PC):** descartada por bloquear recursos durante esperas que podem durar horas e por não ter suporte entre os bancos usados.

## Consequências
- O orquestrador é um componente a mais para operar e precisa ser resiliente: o estado é persistido antes de cada comando, e o envio usa outbox.
- Todos os serviços precisam de idempotência no consumo (`inbox_events`) e de guarda de passo contra mensagens duplicadas ou fora de ordem.
- O BDD do fluxo completo fica no repositório do orquestrador.
