package org.beckn.discover.service.response;

/**
 * @deprecated Replaced by {@link org.beckn.discover.service.postgresql.PostgreSQLAssembler}.
 *
 * <p>This class is no longer a Spring bean ({@code @Service} removed) and
 * will be deleted in a future cleanup.  All row-to-catalog transformation now
 * lives inside the {@code postgresql} package alongside the query logic that
 * produces the rows, eliminating the cross-package dependency on
 * {@link org.beckn.discover.service.postgresql.QueryBuilderHelper#MATCHING_OFFERS_ALIAS}.</p>
 */
@Deprecated(since = "plan2", forRemoval = true)
public class PostgreSQLResponseAssembler {
    // intentionally empty — no longer a Spring bean
}
