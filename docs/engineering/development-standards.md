# Padrões de Desenvolvimento

Regras para os serviços `billing`, `stock`, `orders` e `orchestrator` (Kotlin, Java 25, Spring Boot 4.1). Em caso de conflito, a spec do serviço prevalece.

## 1. Kotlin

**Faça**
- Pacotes em minúsculas sem underscore (raiz `com.fiap.mechanicalhub`); classes em PascalCase; funções e variáveis em camelCase; constantes (`const val`) em SCREAMING_SNAKE_CASE.
- Nomes completos e descritivos (`quantidadeDeItens`, não `qtdItens`).
- `val` e coleções imutáveis (`listOf`, `mapOf`) por padrão.
- Tipos não nulos por padrão; `?` só quando o valor pode de fato faltar.
- `data class` para objetos que carregam dados.
- `sealed class`/`sealed interface` para conjuntos finitos de estados ou resultados, tratados com `when` exaustivo (sem `else`).
- `when` como expressão no lugar de cadeias de `if-else`.
- String templates, named arguments em chamadas com muitos parâmetros e default parameters no lugar de sobrecargas.
- Extension functions no lugar de classes utilitárias estáticas.
- Scope functions no caso certo: `let` para nulos, `apply` para configurar objetos.
- ktlint e detekt no build e no CI.

**Não faça**
- `var` ou `mutableListOf` sem necessidade.
- `!!` (use `?:`, `requireNotNull` ou modele o tipo como não nulo).
- `equals`/`hashCode`/`toString` manuais em classes de dados.
- Scope functions aninhadas; se atrapalha a leitura, use variáveis intermediárias.
- Código no estilo Java traduzido (getters/setters, Lombok, `static` utilitários).
- Magic numbers e funções longas ou com alta complexidade.

## 2. Arquitetura (Clean Architecture)

Camadas: `domain/` → `application/` → `infrastructure/`. Dependências apontam sempre para dentro.

**Faça**
- `domain/`: entidades, value objects, regras e máquinas de estado em Kotlin puro.
- `application/`: um caso de uso por ação de negócio; define as portas (interfaces) de que precisa.
- `infrastructure/`: adapters das portas (JPA/Mongo, SQS, HTTP, Mercado Pago, notificações), controllers, listeners e configuração Spring.
- Controllers e `@SqsListener` finos: convertem a entrada, chamam o caso de uso, convertem a saída.
- Modelos de persistência e DTOs separados do domínio, com mapeamento explícito.
- Transação delimitada no caso de uso; alteração de estado e outbox na mesma transação.
- Exceções de domínio específicas, traduzidas para HTTP/mensagem na `infrastructure/`.
- Configuração por variáveis de ambiente; nada sensível no código.

**Não faça**
- Anotações de Spring, JPA, Mongo ou Jackson no `domain/`.
- Regra de negócio em controller, listener, repositório ou mapper.
- Caso de uso chamando outro caso de uso.
- Expor entidade de persistência na API ou em mensagens.
- `domain/` ou `application/` importando algo de `infrastructure/`.

## 3. Testes

**Princípios FIRST**
- **Fast:** unitários rodam em milissegundos, sem Spring, banco ou rede.
- **Independent:** nenhum teste depende de outro ou da ordem de execução.
- **Repeatable:** mesmo resultado em qualquer máquina; data e hora injetadas (`Clock`), sem dependência de ambiente.
- **Self-validating:** passa ou falha por asserção, sem conferência manual de log.
- **Timely:** escrito junto com o código, na mesma etapa.

**Estrutura**
- Corpo do teste em três blocos: Arrange / Act / Assert, separados por linha em branco.
- Nome no formato given-when-then, com backticks: `` `given pedido pendente when aprovar then muda para APROVADO` ``.
- Um comportamento por teste; um único Act.

**Faça**
- Unitários para `domain/` e `application/` com fakes ou mocks das portas.
- Integração com Testcontainers (PostgreSQL/MongoDB + LocalStack) e WireMock para APIs externas.
- Testar casos de erro, idempotência e concorrência descritos na spec, não só o caminho feliz.
- Builders ou fixtures para montar dados de teste.
- Cobertura ≥ 80% (JaCoCo) como consequência, não como meta.

**Não faça**
- Testar getters, data classes ou o framework.
- `Thread.sleep` (use polling com timeout, ex.: Awaitility).
- Dados compartilhados e mutáveis entre testes.
- Mockar o que pertence ao próprio domínio.
- Lógica (`if`, laços) dentro do teste.
