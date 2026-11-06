# ADR-0004: Banco de Dados por Microsserviço

## Status
Aceito

## Contexto
Na Fase 4, o Mechanical Hub passa a ter quatro microsserviços (`mechanical-hub-orders`, `mechanical-hub-stock`, `mechanical-hub-billing` e `mechanical-hub-orchestrator`), coordenados por uma Saga orquestrada. Cada serviço é dono dos seus dados e nenhum acessa diretamente o banco de outro ([`architecture_definition_guide.md`](../architecture/architecture_definition_guide.md)). O desafio exige **ao menos um banco relacional e um não relacional** no conjunto dos serviços.

A disposição das entidades de cada serviço, derivada dos fluxos de transação, está em [`data_model.md`](../design/data_model.md). Dela saíram os critérios usados nesta decisão:

- **Consistência:** todos os fluxos dependem de garantias transacionais locais (outbox na mesma transação da alteração de domínio, deduplicação de mensagens, transições de estado condicionais). Entre consistência e disponibilidade, **consistência prevalece em todos os serviços**, inclusive no orquestrador: uma saga duplicada ou um passo aplicado fora de ordem corrompe a transação distribuída inteira.
- **Formato dos dados e padrão de acesso:** quantos agregados o serviço tem, se eles se relacionam, e se são lidos e gravados inteiros por chave ou consultados por vários atributos.
- **Volume:** estimativa de 10 unidades × até 350 ordens/mês = 3.500 ordens/mês. Com cerca de 15 mensagens por saga, são aproximadamente 52.500 mensagens/mês, ou **1.750 por dia (≈ 0,02 por segundo)**. Mesmo com pico de 100× e três escritas por mensagem, o volume fica abaixo de 10 escritas por segundo, sem pressão sobre nenhuma tecnologia de banco. **Escala não é critério de escolha** nesta decisão.

Como qualquer serviço poderia funcionar sobre SQL, o requisito do NoSQL foi atendido escolhendo o serviço cujo modelo de dados tem **justificativa arquitetural própria** para o modelo de documento, e não um serviço aleatório.

## Decisão

| Serviço | Banco | Modelo |
|---|---|---|
| `mechanical-hub-orders` | PostgreSQL 16 | Relacional |
| `mechanical-hub-stock` | PostgreSQL 16 | Relacional |
| `mechanical-hub-billing` | PostgreSQL 16 | Relacional |
| `mechanical-hub-orchestrator` | **MongoDB** (replica set) | Documento |

Configuração do MongoDB orientada a consistência:

- **Replica set obrigatório** em todos os ambientes (produção, local e testes), pré-requisito para transações multi-documento.
- `writeConcern: majority` e `readConcern: majority` (transações com `readConcern: snapshot`).
- Leitura sempre no primário (`readPreference: primary`).

Modelagem no orquestrador (detalhada em [`data_model.md`](../design/data_model.md) §5):

- **`transaction_register`:** um documento por saga, com as listas (`repairs`, `reservedMaterials`) e os dados de orçamento e pagamento embutidos. Índice único em `orderId`. A guarda de passo é uma atualização condicional (`findOneAndUpdate` filtrando por `currentStep`).
- **`transaction_history`:** histórico append-only de cada mensagem processada pela saga (momento de chegada, passo antes e depois, resultado), gravado na mesma transação. Base para auditoria e consultas de acompanhamento.
- **Outbox em coleção separada (`outbox_events`), gravada na mesma transação multi-documento** que a saga e o histórico. A ordem de publicação por ordem de serviço é garantida por um contador `seq` no documento da saga, incrementado na mesma atualização pelo número de eventos gravados no outbox, e cada evento recebe um valor dessa faixa. A liderança do relay passa a usar um lock com expiração (*lease*) em vez de `pg_try_advisory_lock`.
- **Sem coleção de deduplicação separada (decidido em 2026-10-08):** a `transaction_history` tem índice único em `messageKey` e deduplica as mensagens recebidas. Nos serviços PostgreSQL essa função é da tabela `inbox_events`.
- **`outbox_events` permanente** em todos os serviços: os enviados ficam com status `SENT`, sem exclusão.

## Justificativa

### `mechanical-hub-stock` — PostgreSQL
É o serviço com **maior exigência de consistência**: débito e crédito concorrentes de saldo (`AVAILABLE`/`RESERVED`), a invariante de prioridade FIFO das pendências e a redistribuição na devolução de estoque. Os fluxos dependem de transações que alteram várias linhas e tabelas ao mesmo tempo (`stock`, `stock_movements`, `stock_pending_items`, outbox), de `SELECT ... FOR UPDATE` em ordem crescente de `material_id` para evitar deadlock e de `pg_advisory_xact_lock` por ordem. O modelo é relacional por natureza (material → saldo, movimentações, pendências, materiais por reparo).

### `mechanical-hub-orders` — PostgreSQL
Tem o **modelo mais relacional do sistema**: clientes, veículos, funcionários, perfis, ordens e reparos ligados por chaves estrangeiras. As consultas atravessam essas relações (ordem com cliente, veículo e reparos) e filtram por vários atributos (status, cliente, responsável, número da ordem). As transições de status usam controle otimista (`version`). As tabelas `users`/`profiles` já são lidas em SQL pela Lambda de autenticação.

### `mechanical-hub-billing` — PostgreSQL
Foi avaliado como candidato a NoSQL, porque o orçamento com seus itens é um documento imutável e lido inteiro. Prevaleceu o SQL porque:

- o serviço tem **três agregados que se referenciam** (catálogo de preços → orçamento → pagamento);
- o pagamento depende de **transição condicional sob lock de linha** para resolver a corrida entre o webhook do Mercado Pago e o job de expiração (`FOR UPDATE` + `PENDING → estado final`);
- dados financeiros tendem a consultas de conciliação e somatórios por período, um ponto forte do modelo relacional.

### `mechanical-hub-orchestrator` — MongoDB
- **Um único agregado, sem relações:** a saga é lida e gravada inteira, sempre pela chave (`orderId`/`transactionId`). Não há joins.
- **Estado semiestruturado que cresce ao longo do fluxo:** cada passo acrescenta dados (reparos, materiais reservados, orçamento, link e pagamento). Novos passos ou cenários de compensação acrescentam campos ao documento sem migration de schema.
- **Histórico append-only:** a trilha de mensagens de cada saga é naturalmente uma sequência de documentos, consultável por ordem e por agregação (por exemplo, tempo médio em cada passo).
- **As garantias necessárias existem no MongoDB:** índice único (uma saga por ordem), atualização condicional atômica (guarda de passo), transação multi-documento (saga + histórico + outbox) e índices únicos para deduplicação.
- **Consistência acima de disponibilidade:** com replica set, `majority` na escrita e na leitura e leitura no primário, o MongoDB recusa escritas sem quórum em vez de aceitar estado divergente, o comportamento desejado para a saga.

## Alternativas Consideradas
- **Orquestrador em PostgreSQL (com `JSONB` para as listas):** atenderia tecnicamente, como qualquer serviço sobre SQL, mas deixaria o requisito de NoSQL para um serviço com modelo menos adequado a documentos.
- **Billing em MongoDB (no lugar do orquestrador ou além dele):** descartado pelos motivos da seção anterior. Um segundo NoSQL não traria ganho que compensasse operar duas tecnologias de banco em mais serviços.
- **Amazon DynamoDB no orquestrador:** também atende (escrita condicional, transações, TTL, streams). Descartado porque exige definir os padrões de acesso de antemão (consultas novas sobre o histórico pedem índices secundários planejados) e prende a solução à AWS. O MongoDB oferece consultas ad hoc e agregações sobre o histórico.
- **Banco colunar (Cassandra / Amazon Keyspaces):** descartado. O modelo prioriza disponibilidade, e escritas condicionais (*lightweight transactions*) são custosas e limitadas, o oposto da prioridade de consistência da saga.
- **Outbox embutido no documento da saga (sem transação multi-documento):** dispensaria a transação e o contador de sequência, mas o relay passaria a varrer sagas em vez de uma fila de eventos e o documento cresceria a cada mensagem. Preferiu-se a coleção separada; a escolha pode ser revisitada na implementação.

## Consequências
- Duas tecnologias de banco para operar, provisionar e monitorar. O relay do outbox e o lock de liderança do orquestrador têm implementação diferente da dos três serviços SQL (sem `BIGSERIAL` nem advisory lock).
- O orquestrador precisa declarar um `MongoTransactionManager` no Spring. Sem ele, `@Transactional` não envolve as escritas no MongoDB e a atomicidade saga + outbox se perde sem erro aparente.
- Transações abortadas por conflito (`TransientTransactionError`) devem ser classificadas como erro transitório e cobertas pelo retry bloqueante do consumidor ([`failure_handling.md`](../design/failure_handling.md)).
- Os testes de integração do orquestrador sobem o MongoDB como replica set (Testcontainers).
- Índice em `status + currentStep + updatedAt` em `transaction_register` para localizar sagas paradas.
- Documentos ajustados em 2026-10-06: `td_finished_os.md` §0 (operações do orquestrador no MongoDB), demais TDs (guarda de passo via `findOneAndUpdate`), `saga_transaction_status.md`, `failure_handling.md` (erros transitórios do MongoDB, relay e retenção via TTL) e `architecture_definition_guide.md` (seção de bancos de dados).

## Pendências
Situação em 2026-10-07 (detalhes de implantação em [`infrastructure.md`](../infrastructure/infrastructure.md)):

| Tema | Situação |
|---|---|
| Onde o MongoDB roda | **Resolvido:** DocumentDB não é liberado no AWS Academy Lab; MongoDB roda como StatefulSet no EKS (replica set de um nó), provisionado pelo `mechanical-hub-database`. |
| Separação física dos bancos PostgreSQL | **Resolvido:** uma instância RDS por serviço. O RDS já existente passa a ser o banco do `orders`. |
| `transaction_history` × `processed_messages` | **Resolvido (2026-10-08):** a `transaction_history`, com índice único em `messageKey`, faz a deduplicação no orquestrador. Nos demais serviços a tabela foi renomeada para `inbox_events`. |
| Formato das mensagens | **Resolvido (2026-10-10):** JSON com contratos versionados ([`infrastructure.md`](../infrastructure/infrastructure.md) §4). |
| `users` / `profiles` e a Lambda `authenticate` | **Resolvido:** a Lambda continua lendo essas tabelas diretamente no banco do `orders`, como na Fase 3 (exceção consciente, tratada como parte do domínio de `orders`). |
