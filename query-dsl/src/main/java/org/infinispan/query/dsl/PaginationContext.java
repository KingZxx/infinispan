package org.infinispan.query.dsl;

/**
 * @author anistor@redhat.com
 * @since 9.0
 * @deprecated since 10.1. See deprecation note on {@link QueryBuilder}.
 */
@Deprecated
public interface PaginationContext<Context extends PaginationContext> {

   Context startOffset(long startOffset);

   Context maxResults(int maxResults);
}
