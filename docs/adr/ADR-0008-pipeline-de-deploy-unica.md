# ADR-0008: Pipeline de Deploy Única via Reusable Workflows

## Status
Aceito

## Contexto
Subir o ambiente no AWS Lab exigia disparar manualmente o deploy de cada repositório (`infra`, `database`, serviços, `auth`) na ordem certa, e atualizar as credenciais do Lab, que expiram a cada ~4 h, em cada repositório. Além disso, `infra`, `database` e `auth` faziam deploy em todo push na `main`, o que disputa o state e a ordem com um deploy completo.

## Decisão
- Os repositórios ficam na organização GitHub `mechanical-hub-fiap` (nome de exibição: Mechanical Hub | Fiap).
- A pipeline única é o workflow `deploy-all.yml` do `mechanical-hub-infra`, com `workflow_dispatch` (inputs `environment`, `ate_etapa`, `mongodb_persistencia`, `criar_usuarios_e_role`).
- Cada repositório expõe seu `deploy.yml` com `workflow_call`; a pipeline os encadeia com `needs:`: `infra → database → [orders, billing, stock, orchestrator] → auth`.
- Os `deploy.yml` deixam de rodar no push para `main` (continuam manuais). O monolito `mechanical-hub` não faz mais deploy automático.
- Secrets no nível da organização, repassados com `secrets: inherit`. O bucket de state é único: `mechanical-hub-tfstate-<AWS_ACCOUNT_ID>`.
- Referência dos workflows chamados: `@main` (suficiente para o prazo; tag ou SHA é mais seguro).
- Cada workflow chamado usa um grupo de `concurrency` próprio (`deploy-infra-…`, `deploy-database-…`, `deploy-auth-…`), pois o grupo é avaliado no repositório chamador.

## Consequências
- Um disparo sobe o ambiente completo, e uma falha interrompe as etapas seguintes.
- Credenciais do Lab são atualizadas em um só lugar.
- Os repositórios chamados precisam permitir o acesso por outros repositórios da organização.
- Os microsserviços entram como jobs paralelos (`needs: database`) quando tiverem `deploy.yml` com `workflow_call`; o `auth` passa a esperar o `orders`.
- O destroy do ambiente continua manual.
