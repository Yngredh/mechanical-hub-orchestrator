# RFC-0006: Persistência por Serviço — PostgreSQL × MongoDB

## Status
Aceito. A decisão detalhada está na [ADR-0004](../adr/ADR-0004-banco-de-dados-por-microsservico.md).

## Contexto
Cada microsserviço é dono do próprio banco, entre consistência e disponibilidade, consistência prevalece em todos os serviços. O volume estimado (cerca de 1.750 mensagens por dia) não pressiona nenhuma tecnologia, então escala não foi critério.

## Proposta

| Serviço | Banco | Motivo principal |
|---|---|---|
| `orders` | PostgreSQL | Modelo mais relacional do sistema (clientes, veículos, ordens, reparos). |
| `stock` | PostgreSQL | Maior exigência de consistência: saldos concorrentes, FIFO de pendências, transações em várias tabelas. |
| `billing` | PostgreSQL | Três agregados relacionados (preços, orçamento, pagamento) e lock de linha na corrida entre o webhook e o job de expiração. |
| `orchestrator` | MongoDB (replica set) | Um único agregado (a saga), lido e gravado inteiro por chave, com estado que cresce a cada passo e histórico append-only. |

O MongoDB é configurado para consistência: replica set, `majority` na escrita e na leitura, e leitura no primário.

## Alternativas Consideradas
- **Orquestrador em PostgreSQL com `JSONB`:** funcionaria, mas deixaria o NoSQL para um serviço com modelo menos adequado a documentos.
- **Billing em MongoDB:** descartado pelos relacionamentos e pela necessidade de lock de linha.
- **DynamoDB:** exige definir os padrões de acesso de antemão e prende a solução à AWS.
- **Cassandra / Keyspaces:** prioriza disponibilidade, o oposto do que a saga precisa.

## Consequências
- Duas tecnologias de banco para provisionar, operar e monitorar.
- O orquestrador depende de transação multi-documento (saga, histórico e outbox) e precisa de um `MongoTransactionManager` configurado.
- Uma instância RDS por serviço SQL. O MongoDB roda no EKS, porque o DocumentDB não está disponível no AWS Academy Lab. Há um plano B caso o MongoDB não funcione no Lab.
