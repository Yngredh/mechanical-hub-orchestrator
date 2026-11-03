# Mechanical Hub — Infraestrutura da Fase 4

Referência das decisões de infraestrutura e plataforma da Fase 4 (marco M0 do plano macro). Consultar este arquivo antes de provisionar, configurar pipelines ou conectar um serviço à mensageria (SQS) e aos bancos. Decisões de domínio e de fluxo continuam em [`architecture_definition_guide.md`](../architecture/architecture_definition_guide.md); a escolha de banco por serviço está na [ADR-0004](../adr/ADR-0004-banco-de-dados-por-microsservico.md).

Última atualização: 2026-10-10 (mensagens em JSON; em 2026-10-09, Kafka substituído por Amazon SQS).

## 1. Restrições do ambiente (AWS Academy Learner Lab)

Fonte: README do Learner Lab, seção "Service usage and other restrictions" (instruções atualizadas em 2025-06-24). As restrições podem mudar; conferir o README do Lab se algo falhar por permissão.

| Tema | Restrição | Impacto no projeto |
|---|---|---|
| Serviços gerenciados | **Amazon SQS está liberado** (pode assumir a `LabRole`). **Amazon MSK e Amazon DocumentDB não estão na lista** | Mensageria em Amazon SQS (seção 3); MongoDB roda dentro do cluster EKS |
| Regiões | Apenas `us-east-1` e `us-west-2` | Tudo em `us-east-1`, como na Fase 3 |
| EC2 (inclui nós do EKS) | Tamanhos *nano* a *large*; máximo de 9 instâncias e 32 vCPU simultâneos por região; 20 ou mais instâncias desativam a conta | Dimensionar o node group com folga para serviços, MongoDB e observabilidade |
| EKS | O README cita roles com identificador `LabEksClusterRole`; o `mechanical-hub-infra` usa a `LabRole` para cluster e nós e funciona desde a Fase 3 | Nenhuma role nova pode ser criada |
| IAM | Não é possível criar roles (exceto service-linked); `LabRole` e `LabInstanceProfile` já existem | Driver EBS CSI sem IRSA (usa a role dos nós) |
| EBS | Até 100 GB; gp2, gp3, sc1 ou standard; sem PIOPS | Volumes dos StatefulSets em gp3 |
| RDS | PostgreSQL de *nano* a *medium*; gp2 até 100 GB; sem enhanced monitoring; **não para no fim da sessão** | Cada instância RDS consome crédito o tempo todo |
| Lambda | Máximo de 10 execuções simultâneas | Suficiente para as Lambdas de autenticação |
| Sessão | 4 h; recursos persistem entre sessões; EC2 para no fim da sessão e religa na próxima | MongoDB precisa voltar sozinho com os nós; filas SQS não são afetadas |
| Orçamento | Estourar o crédito desativa a conta e apaga tudo; o saldo exibido atualiza a cada 8–12 h | Destruir o que não estiver em uso; monitorar o saldo |

## 2. Repositórios e responsabilidades

| Repositório | Responsabilidade na Fase 4 |
|---|---|
| `mechanical-hub-infra` | Infraestrutura comum: VPC, EKS, ECR (um repositório por serviço), NLB/target groups, stack de observabilidade e **filas Amazon SQS** (com as DLQs) |
| `mechanical-hub-database` | Bancos de dados e suas particularidades (instâncias, usuários, roles, parâmetros). O RDS PostgreSQL já provisionado passa a ser o **banco do `mechanical-hub-orders`** |
| `mechanical-hub-auth` | Lambdas de autenticação e API Gateway, com rotas para os 4 serviços |
| `mechanical-hub-orders`, `-stock`, `-billing`, `-orchestrator` | Código, migrations do próprio banco, Dockerfile, manifestos Kubernetes e pipeline de cada serviço |
| `mechanical-hub` (monolito) | **Mantido como está, apenas como referência.** Não é alterado nem implantado na Fase 4 |

Regras:

- As migrations de cada banco vivem no repositório do serviço dono dele. O `mechanical-hub-orders` terá migrations novas, sem herdar o histórico Flyway do monolito.
- A separação entre `mechanical-hub-infra` (comum) e `mechanical-hub-database` (bancos) da Fase 3 é mantida.

### Registro da Fase 3

O que foi entregue na Fase 3 fica preservado por **tag Git `fase-3`** no commit entregue, em cada repositório da Fase 3 (`mechanical-hub`, `mechanical-hub-infra`, `mechanical-hub-database`, `mechanical-hub-auth`), com release no GitHub. Basta poder consultar a entrega; não é necessário conseguir reimplantar o monolito. Criar as tags antes da primeira alteração da Fase 4 nesses repositórios.

## 3. Componentes de plataforma no cluster

### Regra de containers

**Um container por componente da arquitetura**: cada serviço e cada banco roda no seu próprio container. A exceção são os agentes/coletores do OpenTelemetry, que podem rodar junto do componente que observam (sidecar) para coletar suas métricas e traces.

### Mensageria: Amazon SQS (decidido em 2026-10-09, substitui o Kafka)

**Por quê:** o Kafka compensa em ambientes com muitos produtores e consumidores compartilhando tópicos. Aqui são 4 serviços, com comunicação ponto a ponto entre o orquestrador e cada serviço. O SQS é gerenciado e está liberado no Lab: não ocupa nós do cluster, não depende do driver EBS CSI e é provisionado por Terraform em poucas linhas. O padrão de comunicação não muda (comandos do orquestrador para cada serviço, eventos de volta para o orquestrador, outbox e inbox em cada serviço).

**Filas (todas FIFO):**

| Fila | Produtor | Consumidor | Substitui o tópico |
|---|---|---|---|
| `mechanical-hub-orders-commands.fifo` | orquestrador | `orders` | `orders.commands` |
| `mechanical-hub-stock-commands.fifo` | orquestrador | `stock` | `stock.commands` |
| `mechanical-hub-billing-commands.fifo` | orquestrador | `billing` | `billing.commands` |
| `mechanical-hub-orchestrator-events.fifo` | `orders`, `stock`, `billing` | orquestrador | `orchestrator.events` |

Cada fila tem a sua DLQ (`<nome>-dlq.fifo`; a DLQ de uma fila FIFO também precisa ser FIFO).

**Como os mecanismos do Kafka ficam no SQS:**

| Mecanismo | Kafka (desenho anterior) | SQS |
|---|---|---|
| Ordem por ordem de serviço | Key = `orderId` → mesma partição | **Fila FIFO com `MessageGroupId = orderId`**: mensagens do mesmo grupo são entregues em ordem e uma de cada vez |
| Retry bloqueante | Consumidor não avança o offset da partição | Enquanto uma mensagem de um `orderId` está em processamento ou aguardando nova tentativa, as seguintes **do mesmo `orderId`** ficam retidas; outras ordens seguem normalmente |
| Backoff (1 s → 8 s, 5 retries) | `DefaultErrorHandler` com backoff exponencial | Não confirmar (não apagar) a mensagem e ajustar o *visibility timeout* pela contagem de recebimentos (`ApproximateReceiveCount`) |
| DLQ | Tópico `<tópico>.DLT` publicado pelo próprio consumidor | **DLQ nativa** por *redrive policy*: após `maxReceiveCount = 6` recebimentos (1 + 5 retries) a mensagem vai sozinha para a DLQ |
| Reprocessamento da DLQ | Republicar no tópico de origem | **Redrive nativo** da DLQ para a fila de origem (console ou API) |
| Confirmação | Commit do offset | `DeleteMessage` após o `COMMIT` da transação local |
| Entrega | *at-least-once* | *at-least-once* (FIFO deduplica envios iguais só por 5 min via `MessageDeduplicationId = eventId`); **a `inbox_events` continua necessária** |

**Parâmetros iniciais:** *visibility timeout* de 30 s (maior que o tempo de processamento), *long polling* com `WaitTimeSeconds = 20`, retenção de 4 dias nas filas e 14 dias nas DLQs (a `inbox_events` guarda 30 dias, mais que a retenção da DLQ). Payload máximo de 256 KB por mensagem, folgado para os payloads da saga.

**Acesso e rede:**

- Os pods acessam o SQS com as credenciais da role dos nós (`LabRole`, via metadados da instância), o mesmo mecanismo do driver EBS CSI. Nenhuma chave de acesso em secret: as credenciais do Lab expiram a cada sessão.
- Tráfego dos nós (subnets privadas) até o SQS pelo NAT Gateway que já existe. Um *VPC endpoint* de interface para o SQS é opcional e cobraria por hora; não usar.

**Provisionamento:** `aws_sqs_queue` (FIFO, *redrive policy*) no `mechanical-hub-infra`, com as URLs das filas como outputs lidos pelos pipelines dos serviços. Filas não guardam estado de infraestrutura que precise ser recriado: sobrevivem ao fim da sessão do Lab e são removidas no `terraform destroy`.

**Código dos serviços:** Spring Cloud AWS (`@SqsListener` para consumir, `SqsTemplate` no relay do outbox). O relay envia por `orderId` em ordem crescente de `seq`, e a FIFO preserva essa ordem dentro do grupo.

**Ambiente local e testes:** LocalStack (SQS) no `docker-compose` e o módulo LocalStack do Testcontainers nos testes de integração.

**Observabilidade:** o agente Java do OpenTelemetry instrumenta o SDK da AWS e propaga o contexto de trace nos *message attributes* do SQS (limite de 10 atributos por mensagem; reservar espaço para o trace). Profundidade das filas e das DLQs vem das métricas do CloudWatch (`ApproximateNumberOfMessagesVisible`), lidas no Grafana pela fonte de dados CloudWatch.

### MongoDB (`mechanical-hub-orchestrator`)

- **Uma instância** como StatefulSet no EKS, iniciada como **replica set de um nó** (`--replSet rs0` + `rs.initiate()`), pré-requisito para transações multi-documento.
- `writeConcern: majority`, `readConcern: majority` (transações em `snapshot`), `readPreference: primary` (ADR-0004). Com um nó, "maioria" é o próprio nó.
- Sem tolerância à perda do pod; aceito no contexto do Lab.

### Armazenamento dos StatefulSets

Os dados do MongoDB ficam em PersistentVolumeClaims sobre EBS, o que exige o driver EBS CSI.

**Decisão (vigente até que a implementação prove o contrário):** instalar o driver como **add-on gerenciado do EKS (`aws-ebs-csi-driver`) sem role IAM própria**, usando as credenciais da role dos nós (`LabRole`). A AWS recomenda dar ao driver uma role própria via EKS Pod Identity ou IRSA, mas as duas exigem criar uma role IAM, o que o Lab não permite. O `mechanical-hub-infra` já tem esse add-on implementado, desligado por padrão (`observability_manage_ebs_csi_addon`).

**Ainda não validado no Lab:** confirmar com o teste da seção 8 durante a implementação do M1 antes de depender dele. Se falhar, a alternativa é armazenamento efêmero para o MongoDB, com a massa de dados recriada automaticamente.

Cuidados:

- StorageClass gp3 com `volumeBindingMode: WaitForFirstConsumer`.
- Volume EBS fica preso a uma zona de disponibilidade: o pod precisa voltar a um nó da mesma zona quando os nós religam.
- Definir `requests`/`limits` de CPU e memória para o MongoDB desde o início.

### Capacidade do cluster

**Decisão:** node group com **3 nós `t3.large`** (2 vCPU e 8 GiB cada; até 35 pods por nó), dentro do limite do Lab de 9 instâncias e 32 vCPU. Hoje o `mechanical-hub-infra` usa 3 × `t3.medium` (variável `eks_node_instance_type`). HPA de cada serviço entre 1 e 2 réplicas.

Estimativa aproximada dos *requests* somados, já sem Kafka e Schema Registry: ~2,3 vCPU e ~6,5 GiB (observabilidade ~0,6 vCPU / 1,8 GiB, 4 serviços ~1 vCPU / 3 GiB, MongoDB ~0,25 / 0,75 GiB, pods de sistema ~0,5 / 1 GiB). Com o SQS fora do cluster sobra mais folga; 3 × `t3.large` continua sendo a decisão, e voltar para `t3.medium` pode ser reavaliado se o crédito apertar.

### Orçamento

Crédito disponível no Lab: cerca de **US$ 50**. O ambiente completo só pode ficar ligado durante o desenvolvimento integrado, os testes e a gravação. Alguns recursos cobram por hora mesmo sem uso e **não param no fim da sessão**: o plano de controle do EKS, as instâncias RDS (3), o NAT Gateway e o NLB. Ao terminar um ciclo de trabalho, destruir o ambiente (`terraform destroy` nos repositórios, na ordem inversa da seção 7) e recriá-lo pelos pipelines. Acompanhar o saldo no painel do Lab, lembrando que ele atualiza a cada 8–12 h.

## 4. Formato das mensagens: JSON

**Decisão (2026-10-10):** mensagens em **JSON**, sem schema registry. Com a mensageria simplificada para SQS, JSON é o formato mais simples de lidar: é o formato nativo do corpo da mensagem SQS, fica legível no console e na DLQ e não exige infraestrutura adicional. Substitui a decisão anterior (Avro com Confluent Schema Registry), que dependia do Kafka.

- **Contratos versionados:** cada mensagem é um DTO com campo `version`; a cópia dos contratos fica em cada serviço (seção 6).
- **Envelope (corpo):** `eventId`, `transactionId`, `orderId`, `type`, `version`, `timestamp` e o payload da mensagem.
- **Message attributes:** contexto de trace (OpenTelemetry).
- Mensagem que não desserializa ou viola o contrato é erro permanente: vai direto para a DLQ ([`failure_handling.md`](../design/failure_handling.md) seção 1).

Alternativas descartadas: Avro sem registry (corpo em Base64, sem ganho real com cópia local dos contratos) e AWS Glue Schema Registry (mais configuração, integração com SQS menos comum).

## 5. Bancos de dados

| Serviço | Banco | Hospedagem | Provisionado por | Migrations |
|---|---|---|---|---|
| `mechanical-hub-orders` | PostgreSQL 16 | RDS já existente | `mechanical-hub-database` | Repositório `mechanical-hub-orders` (novas) |
| `mechanical-hub-stock` | PostgreSQL 16 | RDS próprio (uma instância por serviço) | `mechanical-hub-database` | Repositório `mechanical-hub-stock` |
| `mechanical-hub-billing` | PostgreSQL 16 | RDS próprio (uma instância por serviço) | `mechanical-hub-database` | Repositório `mechanical-hub-billing` |
| `mechanical-hub-orchestrator` | MongoDB (replica set de 1 nó) | StatefulSet no EKS | `mechanical-hub-database` (lê o cluster do state do `mechanical-hub-infra`) | Índices e TTL no repositório `mechanical-hub-orchestrator` |

Nenhum serviço acessa o banco de outro; cada serviço tem seu próprio usuário/credencial.

Decisões complementares (2026-10-07):

- **Uma instância RDS por serviço SQL** (3 no total). Cada instância consome crédito do Lab o tempo todo, inclusive fora das sessões: parar ou destruir quando não houver uso.
- **A Lambda `authenticate` continua lendo `users`/`profiles` diretamente no banco do `orders`**, como na Fase 3, com a role somente leitura `mechanical_hub_auth`. É a exceção consciente à regra "nenhum serviço acessa o banco de outro": a Lambda é tratada como parte do domínio de `orders` (borda de autenticação dos funcionários), sem depender do serviço estar no ar. Como as migrations do `orders` são novas, as tabelas, as colunas lidas pela Lambda (`document_number` etc.) e o GRANT da role precisam ser recriados nelas.

### Catálogo e massa de dados

- **Catálogo fixo na Fase 4:** reparos, materiais, materiais por reparo e preços são criados pelas **migrations de cada serviço**, com os **mesmos identificadores** nos três bancos (`repairId` no `orders`, `stock` e `billing`; `materialId` no `stock` e `billing`). Assim os dados já nascem sincronizados. Não há endpoint de cadastro de catálogo; a evolução está registrada como requisito futuro (FR-08), fora do escopo da Fase 4.
- **Massa de demonstração** (funcionários, clientes, veículos, saldos de estoque) também por migration ou script de carga de cada serviço, incluindo ao menos um material com estoque insuficiente para demonstrar a pendência.
- Manter uma tabela única de referência dos ids do catálogo (por exemplo, em `data_model.md`) para que as migrations dos três serviços usem os mesmos valores.

## 6. Contratos, testes e qualidade no CI

| Tema | Decisão |
|---|---|
| Contratos das mensagens | **Cópia em cada serviço**: cada repositório guarda os contratos que publica e consome (formato conforme a seção 4). Sem repositório nem biblioteca de contratos. Ao alterar um contrato, atualizar a cópia em todos os serviços que o usam no mesmo ciclo de PRs |
| Teste BDD do fluxo completo | **No repositório `mechanical-hub-orchestrator`**: cenários Cucumber que descrevem a saga inteira (caminho feliz e compensações), com SQS (LocalStack) e MongoDB em Testcontainers; os demais serviços são simulados pelas mensagens que publicariam. Roda no CI de todo PR e conta para a cobertura do orquestrador |
| Demonstração do fluxo real (vídeo) | Execução manual pelo API Gateway (collection Postman), com o pagamento feito no sandbox do Mercado Pago pelo navegador, acompanhada pelos traces no Tempo e pelo dashboard da saga no Grafana |
| Qualidade no CI | **SonarQube Cloud** (versão SaaS do SonarQube, antes chamada SonarCloud), gratuito para repositórios públicos: um passo de análise em cada pipeline, cobertura lida do JaCoCo e quality gate (cobertura ≥ 80%) como checagem obrigatória do PR. Nada a hospedar no cluster |

## 7. Ordem de provisionamento (ambiente zerado)

1. Credenciais do Lab atualizadas nos secrets da organização `mechanical-hub-fiap`.
2. `mechanical-hub-infra`: VPC, EKS (3 × `t3.large`, com driver EBS CSI), ECR, NLB, observabilidade.
3. `mechanical-hub-infra`: filas SQS FIFO e DLQs (outputs com as URLs).
4. `mechanical-hub-database`: PostgreSQL dos 3 serviços SQL e MongoDB.
5. Pipelines dos 4 serviços: migrations, imagem no ECR, deploy no EKS, smoke test.
6. `mechanical-hub-auth`: Lambdas e API Gateway.
7. Webhook do Mercado Pago apontado para a URL atual do API Gateway.
8. Carga da massa de dados da demonstração.

**Estratégia de deploy (pipeline única).** Os passos 2 a 6 são executados por um único disparo do workflow *Deploy completo* (`deploy-all.yml`, em `mechanical-hub-infra`), que chama o `deploy.yml` de cada repositório como *reusable workflow*: `infra → database → [orders, billing, stock, orchestrator] → auth`. Os `deploy.yml` não rodam mais no push para `main`; o monolito `mechanical-hub` não faz mais deploy automático. Os secrets (inclusive as credenciais do Lab) ficam na organização, e `secrets: inherit` os repassa. Ver ADR-0008.

## 8. Teste do driver EBS CSI no Lab

Objetivo: confirmar que PVCs sobre EBS funcionam com a role dos nós.

1. Instalar o add-on: `aws eks create-addon --cluster-name <cluster> --addon-name aws-ebs-csi-driver` e aguardar `ACTIVE` em `aws eks describe-addon`.
2. Aplicar uma StorageClass gp3 (`provisioner: ebs.csi.aws.com`, `WaitForFirstConsumer`), um PVC de 1 Gi e um pod que grava em `/data`.
3. Conferir `kubectl get pvc` = `Bound`; recriar o pod e verificar que o arquivo continua lá.
4. Deixar o pod rodando, encerrar a sessão do Lab e conferir na sessão seguinte que o pod voltou com os dados.
5. Limpar (`kubectl delete -f`, que remove o volume).

Por que o teste é necessário: a documentação da AWS ([Driver CSI do Amazon EBS](https://docs.aws.amazon.com/pt_br/eks/latest/userguide/ebs-csi.html)) orienta dar ao driver uma role IAM própria (política `AmazonEBSCSIDriverPolicyV2`) via EKS Pod Identity ou IRSA. As duas exigem **criar uma role**, o que o Lab não permite. Sem role própria, o driver usa as credenciais da role dos nós (a `LabRole`, via metadados da instância). Não é o caminho recomendado pela AWS, mas funciona se a `LabRole` tiver as permissões de EC2 para volumes e se os pods conseguirem ler os metadados da instância (IMDSv2 com hop limit ≥ 2).

Em caso de falha: `kubectl describe pvc` e `kubectl logs -n kube-system deploy/ebs-csi-controller -c ebs-plugin`. `UnauthorizedOperation`/`AccessDenied` em `ec2:CreateVolume` ou `ec2:AttachVolume` = a role dos nós não tem a permissão.

## 9. Pendências

| Tema | Situação |
|---|---|
| Validação do driver EBS CSI | Adiada para a implementação do M1. Segue valendo a decisão da seção 3 (add-on sem role própria, usando a `LabRole` dos nós). Se falhar, recalcular a rota: armazenamento efêmero para o MongoDB, com a massa recriada automaticamente |
| Acesso dos pods ao SQS pela role dos nós | Validar no M1, junto com o driver EBS CSI (mesmo mecanismo: credenciais da `LabRole` via metadados da instância) |
