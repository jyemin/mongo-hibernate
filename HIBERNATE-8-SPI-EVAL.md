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
   `org.hibernate.sql.ast.spi.query.expression.JdbcParameter` and
   `org.hibernate.sql.exec.spi.JdbcParameterBinder` directly; field-path
   resolution goes through `Expression.getColumnReference()`; aggregate
   recognition through `EmbeddableValuedModelPart`; the rejecting upsert
   implements `org.hibernate.sql.spi.mutation.jdbc.JdbcValueDescriptor`;
   parameter member reads dispatch through
   `org.hibernate.sql.ast.spi.query.expression.JdbcParameter` and
   `SqlExpressible`.
8. `SqlTreePrinter` debug logging dropped (internal utility, no equivalent).
9. The no-op update (`TableUpdateNoSet`) detected through the spi
   `TableUpdate` accessors instead of the internal marker class.
10. The struct flatten and assemble walks copied from `StructHelper` into
    `MongoStructJdbcType` as private static methods,
    reduced to what the type uses: no attribute-order mapping, no
    polymorphic embeddables, and associations decomposed through the
    public `ModelPart` contract. The values holder implements the
    incubating `org.hibernate.metamodel.spi.ValueAccess`, the one finding the copy adds.

## Provider-boundary report

`./gradlew validateDialectProviderBoundaries` against this branch's jar
(using the plugin from the PR, with classification metadata generated from
the PR checkout): 67 errors and 8 warnings.

The 67 errors (`MISSING_IMPLEMENT_ROLE`, 23 declarations) are the deliberate
output of the interface-surfacing series: every internal dependency that
could be expressed against an spi interface was converted, so the report
names exactly the contracts that need classification. Per the generated
classification metadata, the 23 declarations fall into two categories.

Classified SPI, `USE` role only (16 declarations). The category is right;
implementing them simply needs the `IMPLEMENT` role:

- `org.hibernate.service.spi.ServiceInitiator`
- `org.hibernate.service.spi.ServiceContributor`
- `org.hibernate.service.spi.Stoppable`
- `org.hibernate.service.spi.Wrapped`
- `org.hibernate.boot.registry.selector.spi.NamedStrategyContributor`
- `org.hibernate.boot.spi.AdditionalMappingContributor`
- `org.hibernate.engine.jdbc.connections.spi.ConnectionProvider`
- `org.hibernate.engine.jdbc.connections.spi.DatabaseConnectionInfo`
- `org.hibernate.metamodel.spi.ValueAccess`
- `org.hibernate.sql.ast.spi.SqlAstNode`
- `org.hibernate.sql.ast.spi.query.expression.Expression`
- `org.hibernate.sql.ast.spi.query.expression.JdbcParameter`
- `org.hibernate.sql.exec.spi.JdbcParameterBinder`
- `org.hibernate.sql.spi.mutation.SelfExecutingUpdateOperation`
- `org.hibernate.sql.spi.mutation.jdbc.JdbcValueDescriptor`
- `org.hibernate.sql.spi.mutation.MutationOperation`

Classified API with no roles (7 declarations). These are public types in
plain, non-spi packages, and the classifier resolved them as
application-facing contracts; under the model, a provider implementing an
API declaration is a policy violation. Custom identifier generators and
custom service initiators have been documented extension points for years,
so this looks like the unannotated-public-type defaulting rule sweeping up
classic extension points rather than intent. These need reclassification
to SPI with `IMPLEMENT`, not just a role:

- `org.hibernate.service.Service`
- `org.hibernate.boot.registry.StandardServiceInitiator`
- `org.hibernate.generator.Generator`
- `org.hibernate.generator.BeforeExecutionGenerator`
- `org.hibernate.query.sqm.function.SetReturningFunctionRenderer`
- `org.hibernate.query.sqm.function.AbstractSqmSelfRenderingSetReturningFunctionDescriptor`
- `org.hibernate.dialect.function.array.AbstractArrayIncludesFunction`

Hibernate's own `ConnectionProvider` (SPI, `USE`) extending `Service`
(API) is a cross-category edge of the kind their
`FORBIDDEN_CATEGORY_DEPENDENCY` validation is meant to catch, which is
further evidence the API classifications are unintended.

The six remaining warning declarations have no local route; they are
runtime types Hibernate instantiates and hands to the extension:

- `org.hibernate.persister.entity.JoinedSubclassEntityPersister`,
  `org.hibernate.persister.entity.SingleTableEntityPersister`,
  `org.hibernate.persister.entity.UnionSubclassEntityPersister`
  (inheritance-strategy detection; needs a supported strategy accessor)
- `org.hibernate.query.sqm.sql.internal.SqmParameterInterpretation`
  (recognition; needs a hook or classification)
- `org.hibernate.boot.registry.StandardServiceRegistryBuilder` (handed over
  by `org.hibernate.service.spi.ServiceContributor`'s own signature)
- `org.hibernate.engine.jdbc.connections.spi.DatabaseConnectionInfo` (the
  extension implements it; the warnings are those overrides)

## Upstream asks

1. The `IMPLEMENT` role for the 16 SPI-classified declarations above, and
   reclassification of the 7 API-classified ones (the defaulting rule for
   unannotated public types in plain packages, or deliberate
   reclassification of the classic extension points among them).
2. A `JdbcParameterFactory`: `limitParameter`/`offsetParameter` for the
   offset and limit parameters, plus a general
   `parameter(ColumnReference, ParameterUsage)` whose product exposes usage.
   That removes the last self-built parameters and all
   `ColumnValueParameter` references.
3. A supported inheritance-strategy query on the entity mapping contract.
4. `isParameterInterpretation(Expression)` exposed beyond
   `AbstractSqlAstTranslator` (a static utility or a default method on the
   `SqlAstTranslator` interface). It is already the sanctioned recognition
   for query-parameter operands, but as a protected final member of the
   SQL-rendering base it is unavailable to direct implementations, which
   otherwise must name the internal
   `org.hibernate.query.sqm.sql.internal.SqmParameterInterpretation` to
   recognize them.
5. Classification of `org.hibernate.boot.registry.StandardServiceRegistryBuilder` and
   `org.hibernate.engine.jdbc.connections.spi.DatabaseConnectionInfo`.
6. Two structural findings: `ColumnValueParameter` sits in the spi
   `sql.ast.spi.model` package but extends the internal
   `AbstractJdbcParameter` without redeclaring `accept`,
   `getParameterBinder`, `getParameterId`, or `getJdbcMapping`, forcing
   interface casts on every provider; and `Dialect#contributeDefaultProperties`
   cannot influence `hibernate.flush.queue.type` because a service initiator
   consumes that setting before Dialect defaults merge.
7. The graph-based flush queue regression: entity deletes execute one
   statement per row instead of batching, observable to any driver.
