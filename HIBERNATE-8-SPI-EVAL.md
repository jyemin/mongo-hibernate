# hibernate-8-spi-eval branch state

This branch migrates the MongoDB Extension for Hibernate ORM to the
Hibernate 8.0 SPI restructure in hibernate/hibernate-orm PR 13299
(branch `ast-translator-packages-80`, HHH-20747 and HHH-20748).
It is an evaluation branch, not production work.

## Build and test state

The extension compiles against `org.hibernate.orm:hibernate-platform:8.0.0-SNAPSHOT`
and all root-module tests pass: 450 unit tests and 871 integration tests.
The upstream snapshot must be published to local mavenLocal from a checkout
of the PR head (currently commit 49d00fa783):

    cd <hibernate-orm checkout>
    ./gradlew :hibernate-core:publishToMavenLocal :hibernate-testing:publishToMavenLocal \
        :hibernate-dialect-testkit:publishToMavenLocal :hibernate-platform:publishToMavenLocal \
        :hibernate-community-dialects:publishToMavenLocal -x :hibernate-community-dialects:javadoc

Publishing `hibernate-community-dialects` requires skipping javadoc because the
branch has three javadoc errors in that module.

Two known limitations:

- The spring-boot autoconfigure module's integration tests fail under 8.0.
  Hibernate 8 brings Jakarta Persistence 4.0 while Spring Boot 4.0.7 and
  Spring Data are built against 3.2, so Spring Data repository calls fail with
  `NoSuchMethodError: EntityManager.createQuery(CriteriaQuery)`.
  This is an ecosystem-timing blocker for the starter, independent of this
  branch's changes.
- The extension defaults `hibernate.flush.queue.type` to `legacy`
  (see the service contributor in
  `src/main/java/com/mongodb/hibernate/internal/service/StandardServiceRegistryScopedState.java`).
  Hibernate 8's default graph-based flush queue does not batch entity deletes
  and issues extra JDBC metadata probes from the session path. The default is
  removable once delete batching works in the graph-based queue.

## Commit series

1. SPI migration: package moves (`sql.ast.tree` to `sql.ast.spi.query`, the
   `sql.model` split, `SqlAppender` to `sql.spi`), the request-based
   `SqlAstTranslatorFactory`, Dialect hook ports (`contributeTypes`,
   `ArraySupport`, `TemporalFormatSupport`, `UniqueDelegate`,
   request-based `createOptionalTableUpdateOperation`), and the
   `JdbcLockingApplication`/`JdbcPaginationApplication` select constructor.
2. Behavioral fixes found by the test run: the QueryOptions flush-mode guard
   (8.0 returns the JPA default instead of null-unless-set, so the guard
   rejected every boot), and the rejecting upsert operation's
   parameter-descriptor table.
3. The flush-queue legacy pin described above.
4. `AbstractMqlTranslator` extends the classified `AbstractSqlAstWalker`
   instead of implementing `SqlAstWalker` directly.
5. JDBC operation construction ported to the new
   `org.hibernate.sql.exec.spi.JdbcOperations` factory; the translators hold
   their `SqlAstTranslationRequest`.
6. Ports off internals with supported replacements:
   `StandardAggregateSupport`, `StandardDdlTypes`, `QueryOptions.NONE`,
   and the boot-model `Component` check instead of `ComponentType`.
7. Interface-surfacing conversions: the offset and limit parameters implement
   `JdbcParameter` and `JdbcParameterBinder` directly; field-path resolution
   goes through `Expression.getColumnReference()`; aggregate recognition
   through `EmbeddableValuedModelPart`; the rejecting upsert implements
   `JdbcValueDescriptor`; parameter member reads dispatch through
   `JdbcParameter` and `SqlExpressible`.
8. `SqlTreePrinter` debug logging dropped (internal utility, no equivalent).
9. The no-op update (`TableUpdateNoSet`) detected through the spi
   `TableUpdate` accessors instead of the internal marker class.

## Provider-boundary report

`./gradlew validateDialectProviderBoundaries` against this branch's jar
(using the plugin from the PR, with classification metadata generated from
the PR checkout): 65 errors and 11 warnings.

The 65 errors (`MISSING_IMPLEMENT_ROLE`, 22 declarations) are the deliberate
output of commit series 7: every internal dependency that could be expressed
against an spi interface was converted, so the report now names exactly the
contracts that need classification. The declarations:

- Boot and service:
  - `StandardServiceInitiator`
  - `ServiceInitiator`
  - `ServiceContributor`
  - `Service`
  - `NamedStrategyContributor`
  - `AdditionalMappingContributor`
  - `ConnectionProvider`
  - `Stoppable`
  - `Wrapped`
  - `DatabaseConnectionInfo`
- Generation:
  - `Generator`
  - `BeforeExecutionGenerator`
- Functions:
  - `AbstractSqmSelfRenderingSetReturningFunctionDescriptor`
  - `SetReturningFunctionRenderer`
  - `AbstractArrayIncludesFunction`
- Mutation operations:
  - `MutationOperation`
  - `SelfExecutingUpdateOperation`
- Parameter and descriptor surface:
  - `JdbcParameter`
  - `JdbcParameterBinder`
  - `Expression`
  - `SqlAstNode`
  - `JdbcValueDescriptor`

Six of the seven warning declarations have no local route; they are
runtime types Hibernate instantiates and hands to the extension:

- `Joined`/`SingleTable`/`UnionSubclassEntityPersister` (inheritance-strategy
  detection; needs a supported strategy accessor)
- `SqmParameterInterpretation` (recognition; needs a hook or classification)
- `StandardServiceRegistryBuilder` (handed over by `ServiceContributor`'s
  own signature)
- `DatabaseConnectionInfo` (the extension implements it; the warnings are
  those overrides)

`StructHelper` is the exception: it is a static utility, so copying it into
the extension is a known workaround (the file is Apache-2.0 on the branch).
It is unpleasant rather than impossible: the extension would own around
371 lines of mapping-model traversal logic that upstream maintains today,
and the copy would silently diverge on any upstream change. Left as a
warning so the missing spi contract stays visible.

## Upstream asks

1. The `@SPI` annotation pass over the unannotated integration interfaces
   listed above.
2. A `JdbcParameterFactory`: `limitParameter`/`offsetParameter` for the
   offset and limit parameters, plus a general
   `parameter(ColumnReference, ParameterUsage)` whose product exposes usage.
   That removes the last self-built parameters and all
   `ColumnValueParameter` references.
3. A supported inheritance-strategy query on the entity mapping contract.
4. Classification or a recognition hook for `SqmParameterInterpretation`.
5. Classification of `StructHelper`, `StandardServiceRegistryBuilder`, and
   `DatabaseConnectionInfo`.
6. Two structural findings: `ColumnValueParameter` sits in the spi
   `sql.ast.spi.model` package but extends the internal
   `AbstractJdbcParameter` without redeclaring `accept`,
   `getParameterBinder`, `getParameterId`, or `getJdbcMapping`, forcing
   interface casts on every provider; and `Dialect#contributeDefaultProperties`
   cannot influence `hibernate.flush.queue.type` because a service initiator
   consumes that setting before Dialect defaults merge.
7. The graph-based flush queue regression: entity deletes execute one
   statement per row instead of batching, observable to any driver.
