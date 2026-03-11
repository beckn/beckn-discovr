package org.beckn.discover.service.response;

/**
 * @deprecated Replaced by {@link org.beckn.discover.service.nlweb.NLWebAssembler}.
 *
 * <p>This class is no longer a Spring bean ({@code @Service} removed) and
 * will be deleted in a future cleanup.  NLWeb-specific assembly now lives
 * inside the {@code nlweb} package, co-located with
 * {@link org.beckn.discover.service.nlweb.NLWebTextSearchEngine}.</p>
 */
@Deprecated(since = "plan2", forRemoval = true)
public class NlWebResponseAssembler {
    // intentionally empty — no longer a Spring bean
}
