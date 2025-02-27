package com.linkedin.metadata.search;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.linkedin.common.UrnArrayArray;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.template.LongMap;
import com.linkedin.data.template.StringArray;
import com.linkedin.metadata.Constants;
import com.linkedin.metadata.config.cache.SearchLineageCacheConfiguration;
import com.linkedin.metadata.graph.EntityLineageResult;
import com.linkedin.metadata.graph.GraphService;
import com.linkedin.metadata.graph.LineageDirection;
import com.linkedin.metadata.graph.LineageRelationship;
import com.linkedin.metadata.graph.LineageRelationshipArray;
import com.linkedin.metadata.query.FreshnessStats;
import com.linkedin.metadata.query.SearchFlags;
import com.linkedin.metadata.query.filter.ConjunctiveCriterion;
import com.linkedin.metadata.query.filter.Criterion;
import com.linkedin.metadata.query.filter.Filter;
import com.linkedin.metadata.query.filter.SortCriterion;
import com.linkedin.metadata.search.cache.CachedEntityLineageResult;
import com.linkedin.metadata.search.utils.FilterUtils;
import com.linkedin.metadata.search.utils.QueryUtils;
import com.linkedin.metadata.search.utils.SearchUtils;
import io.opentelemetry.extension.annotations.WithSpan;

import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.cache.Cache;

import static com.linkedin.metadata.search.utils.SearchUtils.applyDefaultSearchFlags;


@RequiredArgsConstructor
@Slf4j
public class LineageSearchService {
  private static final SearchFlags DEFAULT_SERVICE_SEARCH_FLAGS = new SearchFlags()
          .setFulltext(false)
          .setMaxAggValues(20)
          .setSkipCache(false)
          .setSkipAggregates(false)
          .setSkipHighlighting(true);
  private final SearchService _searchService;
  private final GraphService _graphService;
  @Nullable
  private final Cache cache;
  private final boolean cacheEnabled;
  private final SearchLineageCacheConfiguration cacheConfiguration;

  private final ExecutorService cacheRefillExecutor = Executors.newFixedThreadPool(1);

  private static final String DEGREE_FILTER = "degree";
  private static final String DEGREE_FILTER_INPUT = "degree.keyword";
  private static final AggregationMetadata DEGREE_FILTER_GROUP = new AggregationMetadata().setName(DEGREE_FILTER)
      .setDisplayName("Degree of Dependencies")
      .setFilterValues(new FilterValueArray(ImmutableList.of(new FilterValue().setValue("1").setFacetCount(0),
          new FilterValue().setValue("2").setFacetCount(0), new FilterValue().setValue("3+").setFacetCount(0))));
  private static final int MAX_RELATIONSHIPS = 1000000;
  private static final int MAX_TERMS = 50000;

  /**
   * Gets a list of documents that match given search request that is related to the input entity
   *
   * @param sourceUrn Urn of the source entity
   * @param direction Direction of the relationship
   * @param entities list of entities to search (If empty, searches across all entities)
   * @param input the search input text
   * @param maxHops the maximum number of hops away to search for. If null, defaults to 1000
   * @param inputFilters the request map with fields and values as filters to be applied to search hits
   * @param sortCriterion {@link SortCriterion} to be applied to search results
   * @param from index to start the search from
   * @param size the number of search hits to return
   * @return a {@link LineageSearchResult} that contains a list of matched documents and related search result metadata
   */
  @Nonnull
  @WithSpan
  public LineageSearchResult searchAcrossLineage(@Nonnull Urn sourceUrn, @Nonnull LineageDirection direction,
      @Nonnull List<String> entities, @Nullable String input, @Nullable Integer maxHops, @Nullable Filter inputFilters,
      @Nullable SortCriterion sortCriterion, int from, int size, @Nullable Long startTimeMillis,
      @Nullable Long endTimeMillis, @Nullable SearchFlags searchFlags) {

    final SearchFlags finalFlags = applyDefaultSearchFlags(searchFlags, input, DEFAULT_SERVICE_SEARCH_FLAGS);

    long startTime = System.nanoTime();
    log.debug("Cache enabled {}, Input :{}:", cacheEnabled, input);

    // Cache multihop result for faster performance
    final EntityLineageResultCacheKey cacheKey = new EntityLineageResultCacheKey(sourceUrn, direction, startTimeMillis, endTimeMillis, maxHops);
    CachedEntityLineageResult cachedLineageResult = null;

    if (cacheEnabled) {
      try {
        cachedLineageResult = cache.get(cacheKey, CachedEntityLineageResult.class);
      } catch (Exception e) {
        log.warn("Failed to load cacheKey {}", cacheKey, e);
      }
    }

    EntityLineageResult lineageResult;
    FreshnessStats freshnessStats = new FreshnessStats().setCached(Boolean.FALSE);
    if (cachedLineageResult == null || finalFlags.isSkipCache()) {
      maxHops = maxHops != null ? maxHops : 1000;
      lineageResult =
          _graphService.getLineage(sourceUrn, direction, 0, MAX_RELATIONSHIPS, maxHops, startTimeMillis,
              endTimeMillis);
      if (cacheEnabled) {
        cache.put(cacheKey,
            new CachedEntityLineageResult(lineageResult, System.currentTimeMillis()));
      }
    } else {
      lineageResult = cachedLineageResult.getEntityLineageResult();
      freshnessStats.setCached(Boolean.TRUE);
      LongMap systemFreshness = new LongMap();
      systemFreshness.put("LineageGraphCache", cachedLineageResult.getTimestamp());
      freshnessStats.setSystemFreshness(systemFreshness);
      // set up cache refill if needed
      if (System.currentTimeMillis() - cachedLineageResult.getTimestamp() > cacheConfiguration.getTTLMillis()) {
        log.info("Cached lineage entry for: {} is older than one day. Will refill.", sourceUrn);
        Integer finalMaxHops = maxHops;
        this.cacheRefillExecutor.submit(() -> {
          log.debug("Cache refill started.");
          CachedEntityLineageResult reFetchLineageResult = cache.get(cacheKey, CachedEntityLineageResult.class);
          if ((reFetchLineageResult == null)
                  ||
                  ((reFetchLineageResult != null)
                          &&
                          ((System.currentTimeMillis() - reFetchLineageResult.getTimestamp()) > cacheConfiguration.getTTLMillis())
                  )
          ) {
            // we have to refetch
            EntityLineageResult result = _graphService.getLineage(sourceUrn, direction, 0, MAX_RELATIONSHIPS, finalMaxHops, startTimeMillis, endTimeMillis);
            cache.put(cacheKey, result);
            log.debug("Refilled Cached lineage entry for: {}.", sourceUrn);
          } else {
            log.debug("Cache refill not needed. {}", System.currentTimeMillis() - reFetchLineageResult.getTimestamp());
          }
        });
      }
    }

    // set schemaField relationship entity to be its reference urn
    LineageRelationshipArray updatedRelationships = convertSchemaFieldRelationships(lineageResult);
    lineageResult.setRelationships(updatedRelationships);

    // Filter hopped result based on the set of entities to return and inputFilters before sending to search
    List<LineageRelationship> lineageRelationships =
        filterRelationships(lineageResult, new HashSet<>(entities), inputFilters);

    String lineageGraphInfo = String.format("Lineage Graph = time(ms):%s size:%s",
            (System.nanoTime() - startTime) / (1000.0 * 1000.0), lineageRelationships.size());
    startTime = System.nanoTime();
    long numEntities = 0;
    String codePath = null;
    try {
      if ((lineageRelationships.size() > cacheConfiguration.getLightningThreshold()) && ((input == null) || (input.isEmpty()) || (input.equals("*")))) {
        codePath = "lightning";
        // use lightning approach to return lineage search results
        LineageSearchResult lineageSearchResult = getLightningSearchResult(lineageRelationships, input != null
                ? input : "*", inputFilters, sortCriterion, from, size, finalFlags);
        if (!lineageSearchResult.getEntities().isEmpty()) {
          log.debug("Lightning Lineage entity result: {}", lineageSearchResult.getEntities().get(0).toString());
        }
        numEntities = lineageSearchResult.getNumEntities();
        return lineageSearchResult;
      } else {
        codePath = "tortoise";
        if (input.isEmpty()) {
          input = "*";
        }
        LineageSearchResult lineageSearchResult = getSearchResultInBatches(lineageRelationships, input != null ? input : "*",
                inputFilters, sortCriterion, from, size, finalFlags);
        if (!lineageSearchResult.getEntities().isEmpty()) {
          log.debug("Lineage entity result: {}", lineageSearchResult.getEntities().get(0).toString());
        }
        numEntities = lineageSearchResult.getNumEntities();
        return lineageSearchResult;
      }
    } finally {
      log.info("{}; Lineage Search({}) = time(ms):{} size:{}", lineageGraphInfo, codePath,
              (System.nanoTime() - startTime) / (1000.0 * 1000.0), numEntities);
    }
  }

  private LineageSearchResult getLightningSearchResult(List<LineageRelationship> lineageRelationships, String s,
                                                       Filter inputFilters, SortCriterion sortCriterion,
                                                       int from, int size, SearchFlags searchFlags) {

    LineageSearchResult finalResult = new LineageSearchResult()
            .setMetadata(new SearchResultMetadata());
    LineageSearchEntityArray lineageSearchEntityArray = new LineageSearchEntityArray();
    AggregationMetadata entityTypeAgg = new AggregationMetadata()
            .setDisplayName("Type")
            .setName("entity")
            .setFilterValues(new FilterValueArray())
            .setAggregations(new LongMap());
    HashMap<String, Long> entityTypeAggregations = new HashMap<>();
    HashMap<String, Long> platformTypeAggregations = new HashMap<>();
    HashMap<String, Long> environmentAggregations = new HashMap<>();
    AggregationMetadata platformTypeAgg = new AggregationMetadata()
            .setDisplayName("Platform")
            .setName("platform")
            .setFilterValues(new FilterValueArray())
            .setAggregations(new LongMap());
    AggregationMetadata environmentAgg = new AggregationMetadata()
            .setDisplayName("Environment")
            .setName("origin")
            .setFilterValues(new FilterValueArray())
            .setAggregations(new LongMap());
    AggregationMetadataArray aggregationMetadataArray = new AggregationMetadataArray();
    // Aggregations supported by this model
    // entity type
    // platform
    // environment
    int start = 0;
    int numElements = 0;
    for (LineageRelationship relnship: lineageRelationships) {
      start++;
      Urn entityUrn = relnship.getEntity();
      if ((start > from) && (numElements < size)) {
        lineageSearchEntityArray.add(new LineageSearchEntity()
                .setEntity(entityUrn)
                .setDegree(relnship.getDegree())
                .setPaths(relnship.getPaths()));
        numElements++;
      }
      // entityType
      String entityType = entityUrn.getEntityType();
      if (!entityTypeAggregations.containsKey(entityType)) {
        entityTypeAggregations.put(entityType, 0L);
      }
      entityTypeAggregations.put(entityType, entityTypeAggregations.get(entityType) + 1);

      // platform
      String platform = null;
      if (entityType.equalsIgnoreCase("dataset")
              || entityType.equalsIgnoreCase("dashboard")
              || entityType.equalsIgnoreCase("chart")
              || entityType.equalsIgnoreCase("dataFlow")
              || entityType.equalsIgnoreCase("dataJob")
      ) {
        platform = entityUrn.getEntityKey().get(0);
      }
      if ((platform != null) && (!platform.startsWith("urn:li:dataPlatform"))) {
        platform = "urn:li:dataPlatform:" + platform;
      }
      if (platform != null) {
        if (!platformTypeAggregations.containsKey(platform)) {
          platformTypeAggregations.put(platform, 0L);
        }
        platformTypeAggregations.put(platform, platformTypeAggregations.get(platform) + 1);
      }

      // environment
      String environment = null;
      if (entityType.equalsIgnoreCase("dataset")) {
        environment = entityUrn.getEntityKey().get(2);
      }
      if (environment != null) {
        if (!environmentAggregations.containsKey(environment)) {
          environmentAggregations.put(environment, 0L);
        }
        environmentAggregations.put(environment, environmentAggregations.get(environment) + 1);
      }
    }

    aggregationMetadataArray.add(DEGREE_FILTER_GROUP);
    if (platformTypeAggregations.keySet().size() > 0) {
      for (Map.Entry<String, Long> platformCount: platformTypeAggregations.entrySet()) {
        try {
          platformTypeAgg.getFilterValues().add(new FilterValue()
                  .setValue(platformCount.getKey())
                  .setFacetCount(platformCount.getValue())
                  .setEntity(Urn.createFromString(platformCount.getKey()))
          );
          platformTypeAgg.getAggregations().put(platformCount.getKey(), platformCount.getValue());
        } catch (URISyntaxException uriSyntaxException) {
          log.warn("Unexpected exception");
        }
      }
        aggregationMetadataArray.add(platformTypeAgg);
    }
    if (entityTypeAggregations.keySet().size() > 0) {
      for (Map.Entry<String, Long> entityCount: entityTypeAggregations.entrySet()) {
        entityTypeAgg.getFilterValues().add(new FilterValue()
                .setValue(entityCount.getKey())
                .setFacetCount(entityCount.getValue()));
        entityTypeAgg.getAggregations().put(entityCount.getKey(), entityCount.getValue());
      }
      aggregationMetadataArray.add(entityTypeAgg);
    }
    if (environmentAggregations.keySet().size() > 0) {
      for (Map.Entry<String, Long> entityCount: environmentAggregations.entrySet()) {
        environmentAgg.getFilterValues().add(new FilterValue()
                .setValue(entityCount.getKey())
                .setFacetCount(entityCount.getValue()));
        environmentAgg.getAggregations().put(entityCount.getKey(), entityCount.getValue());
      }
      aggregationMetadataArray.add(environmentAgg);
    }
    finalResult.setEntities(lineageSearchEntityArray);
    finalResult.getMetadata().setAggregations(aggregationMetadataArray);
    finalResult.setNumEntities(lineageRelationships.size());
    return finalResult.setFrom(from).setPageSize(size);
  }

  // Necessary so we don't filter out schemaField entities and so that we search to get the parent reference entity
  private LineageRelationshipArray convertSchemaFieldRelationships(EntityLineageResult lineageResult) {
    return lineageResult.getRelationships().stream().map(relationship -> {
      if (relationship.getEntity().getEntityType().equals("schemaField")) {
        Urn entity = getSchemaFieldReferenceUrn(relationship.getEntity());
        relationship.setEntity(entity);
      }
      return relationship;
    }).collect(Collectors.toCollection(LineageRelationshipArray::new));
  }

  private Map<Urn, LineageRelationship> generateUrnToRelationshipMap(List<LineageRelationship> lineageRelationships) {
    Map<Urn, LineageRelationship> urnToRelationship = new HashMap<>();
    for (LineageRelationship relationship : lineageRelationships) {
      LineageRelationship existingRelationship = urnToRelationship.get(relationship.getEntity());
      if (existingRelationship == null) {
        urnToRelationship.put(relationship.getEntity(), relationship);
      } else {
        UrnArrayArray paths = existingRelationship.getPaths();
        paths.addAll(relationship.getPaths());
        existingRelationship.setPaths(paths);
      }
    }
    return urnToRelationship;
  }

  // Search service can only take up to 50K term filter, so query search service in batches
  private LineageSearchResult getSearchResultInBatches(List<LineageRelationship> lineageRelationships,
      @Nonnull String input, @Nullable Filter inputFilters, @Nullable SortCriterion sortCriterion, int from, int size,
      @Nonnull SearchFlags searchFlags) {

    final SearchFlags finalFlags = applyDefaultSearchFlags(searchFlags, input, DEFAULT_SERVICE_SEARCH_FLAGS);

    LineageSearchResult finalResult =
        new LineageSearchResult().setEntities(new LineageSearchEntityArray(Collections.emptyList()))
            .setMetadata(new SearchResultMetadata().setAggregations(new AggregationMetadataArray()))
            .setFrom(from)
            .setPageSize(size)
            .setNumEntities(0);
    List<List<LineageRelationship>> batchedRelationships = Lists.partition(lineageRelationships, MAX_TERMS);
    int queryFrom = from;
    int querySize = size;
    for (List<LineageRelationship> batch : batchedRelationships) {
      List<String> entitiesToQuery = batch.stream()
          .map(relationship -> relationship.getEntity().getEntityType())
          .distinct()
          .collect(Collectors.toList());
      Map<Urn, LineageRelationship> urnToRelationship = generateUrnToRelationshipMap(batch);
      Filter finalFilter = buildFilter(urnToRelationship.keySet(), inputFilters);

      LineageSearchResult resultForBatch = buildLineageSearchResult(
          _searchService.searchAcrossEntities(entitiesToQuery, input, finalFilter, sortCriterion, queryFrom, querySize,
              finalFlags), urnToRelationship);
      queryFrom = Math.max(0, from - resultForBatch.getNumEntities());
      querySize = Math.max(0, size - resultForBatch.getEntities().size());
      finalResult = merge(finalResult, resultForBatch);
    }

    finalResult.getMetadata().getAggregations().add(0, DEGREE_FILTER_GROUP);
    return finalResult.setFrom(from).setPageSize(size);
  }

  @SneakyThrows
  public static LineageSearchResult merge(LineageSearchResult one, LineageSearchResult two) {
    LineageSearchResult finalResult = one.clone();
    finalResult.getEntities().addAll(two.getEntities());
    finalResult.setNumEntities(one.getNumEntities() + two.getNumEntities());

    Map<String, AggregationMetadata> aggregations = one.getMetadata()
        .getAggregations()
        .stream()
        .collect(Collectors.toMap(AggregationMetadata::getName, Function.identity()));
    two.getMetadata().getAggregations().forEach(metadata -> {
      if (aggregations.containsKey(metadata.getName())) {
        aggregations.put(metadata.getName(), SearchUtils.merge(aggregations.get(metadata.getName()), metadata));
      } else {
        aggregations.put(metadata.getName(), metadata);
      }
    });
    finalResult.getMetadata().setAggregations(new AggregationMetadataArray(FilterUtils.rankFilterGroups(aggregations)));
    return finalResult;
  }

  private Predicate<Integer> convertFilterToPredicate(List<String> degreeFilterValues) {
    return degreeFilterValues.stream().map(value -> {
      switch (value) {
        case "1":
          return (Predicate<Integer>) (Integer numHops) -> (numHops == 1);
        case "2":
          return (Predicate<Integer>) (Integer numHops) -> (numHops == 2);
        case "3+":
          return (Predicate<Integer>) (Integer numHops) -> (numHops > 2);
        default:
          throw new IllegalArgumentException(String.format("%s is not a valid filter value for degree filters", value));
      }
    }).reduce(x -> false, Predicate::or);
  }

  private Urn getSchemaFieldReferenceUrn(Urn urn) {
    if (urn.getEntityType().equals(Constants.SCHEMA_FIELD_ENTITY_NAME)) {
      try {
        // Get the dataset urn referenced inside the schemaField urn
        return Urn.createFromString(urn.getId());
      } catch (Exception e) {
        log.error("Invalid destination urn: {}", urn.getId(), e);
      }
    }
    return urn;
  }

  private List<LineageRelationship> filterRelationships(@Nonnull EntityLineageResult lineageResult,
      @Nonnull Set<String> entities, @Nullable Filter inputFilters) {
    Stream<LineageRelationship> relationshipsFilteredByEntities = lineageResult.getRelationships().stream();
    if (!entities.isEmpty()) {
      relationshipsFilteredByEntities = relationshipsFilteredByEntities.filter(
          relationship -> entities.contains(relationship.getEntity().getEntityType()));
    }
    if (inputFilters != null && !CollectionUtils.isEmpty(inputFilters.getOr())) {
      ConjunctiveCriterion conjunctiveCriterion = inputFilters.getOr().get(0);
      if (conjunctiveCriterion.hasAnd()) {
        List<String> degreeFilter = conjunctiveCriterion.getAnd()
            .stream()
            .filter(criterion -> criterion.getField().equals(DEGREE_FILTER_INPUT))
            .flatMap(c -> c.getValues().stream())
            .collect(Collectors.toList());
        if (!degreeFilter.isEmpty()) {
          Predicate<Integer> degreePredicate = convertFilterToPredicate(degreeFilter);
          return relationshipsFilteredByEntities.filter(relationship -> degreePredicate.test(relationship.getDegree()))
              .collect(Collectors.toList());
        }
      }
    }
    return relationshipsFilteredByEntities.collect(Collectors.toList());
  }

  private Filter buildFilter(@Nonnull Set<Urn> urns, @Nullable Filter inputFilters) {
    Criterion urnMatchCriterion = new Criterion().setField("urn")
        .setValue("")
        .setValues(new StringArray(urns.stream().map(Object::toString).collect(Collectors.toList())));
    if (inputFilters == null) {
      return QueryUtils.newFilter(urnMatchCriterion);
    }
    Filter reducedFilters =
        SearchUtils.removeCriteria(inputFilters, criterion -> criterion.getField().equals(DEGREE_FILTER_INPUT));

    // Add urn match criterion to each or clause
    if (!CollectionUtils.isEmpty(reducedFilters.getOr())) {
      for (ConjunctiveCriterion conjunctiveCriterion : reducedFilters.getOr()) {
        conjunctiveCriterion.getAnd().add(urnMatchCriterion);
      }
      return reducedFilters;
    }
    return QueryUtils.newFilter(urnMatchCriterion);
  }

  private LineageSearchResult buildLineageSearchResult(@Nonnull SearchResult searchResult,
      Map<Urn, LineageRelationship> urnToRelationship) {
    AggregationMetadataArray aggregations = new AggregationMetadataArray(searchResult.getMetadata().getAggregations());
    return new LineageSearchResult().setEntities(new LineageSearchEntityArray(searchResult.getEntities()
            .stream()
            .map(searchEntity -> buildLineageSearchEntity(searchEntity, urnToRelationship.get(searchEntity.getEntity())))
            .collect(Collectors.toList())))
        .setMetadata(new SearchResultMetadata().setAggregations(aggregations))
        .setFrom(searchResult.getFrom())
        .setPageSize(searchResult.getPageSize())
        .setNumEntities(searchResult.getNumEntities());
  }

  private LineageSearchEntity buildLineageSearchEntity(@Nonnull SearchEntity searchEntity,
      @Nullable LineageRelationship lineageRelationship) {
    LineageSearchEntity entity = new LineageSearchEntity(searchEntity.data());
    if (lineageRelationship != null) {
      entity.setPaths(lineageRelationship.getPaths());
      entity.setDegree(lineageRelationship.getDegree());
    }
    return entity;
  }

  /**
   * Gets a list of documents that match given search request that is related to the input entity
   *
   * @param sourceUrn Urn of the source entity
   * @param direction Direction of the relationship
   * @param entities list of entities to search (If empty, searches across all entities)
   * @param input the search input text
   * @param maxHops the maximum number of hops away to search for. If null, defaults to 1000
   * @param inputFilters the request map with fields and values as filters to be applied to search hits
   * @param sortCriterion {@link SortCriterion} to be applied to search results
   * @param scrollId opaque scroll identifier to pass to search service
   * @param size the number of search hits to return
   * @return a {@link LineageSearchResult} that contains a list of matched documents and related search result metadata
   */
  @Nonnull
  @WithSpan
  public LineageScrollResult scrollAcrossLineage(@Nonnull Urn sourceUrn, @Nonnull LineageDirection direction,
      @Nonnull List<String> entities, @Nullable String input, @Nullable Integer maxHops, @Nullable Filter inputFilters,
      @Nullable SortCriterion sortCriterion, @Nullable String scrollId, @Nonnull String keepAlive, int size, @Nullable Long startTimeMillis,
      @Nullable Long endTimeMillis, @Nonnull SearchFlags searchFlags) {
    // Cache multihop result for faster performance
    final EntityLineageResultCacheKey cacheKey =
        new EntityLineageResultCacheKey(sourceUrn, direction, startTimeMillis, endTimeMillis, maxHops);
    CachedEntityLineageResult cachedLineageResult = cacheEnabled
        ? cache.get(cacheKey, CachedEntityLineageResult.class) : null;
    EntityLineageResult lineageResult;
    if (cachedLineageResult == null) {
      maxHops = maxHops != null ? maxHops : 1000;
      lineageResult = _graphService.getLineage(sourceUrn, direction, 0, MAX_RELATIONSHIPS, maxHops,
          startTimeMillis, endTimeMillis);
      if (cacheEnabled) {
        cache.put(cacheKey, new CachedEntityLineageResult(lineageResult, System.currentTimeMillis()));
      }
    } else {
      lineageResult = cachedLineageResult.getEntityLineageResult();
      if (System.currentTimeMillis() - cachedLineageResult.getTimestamp() > cacheConfiguration.getTTLMillis()) {
        log.warn("Cached lineage entry for: {} is older than one day.", sourceUrn);
      }
    }

    // set schemaField relationship entity to be its reference urn
    LineageRelationshipArray updatedRelationships = convertSchemaFieldRelationships(lineageResult);
    lineageResult.setRelationships(updatedRelationships);

    // Filter hopped result based on the set of entities to return and inputFilters before sending to search
    List<LineageRelationship> lineageRelationships =
        filterRelationships(lineageResult, new HashSet<>(entities), inputFilters);

    return getScrollResultInBatches(lineageRelationships, input != null ? input : "*", inputFilters, sortCriterion,
        scrollId, keepAlive, size, searchFlags);
  }

  // Search service can only take up to 50K term filter, so query search service in batches
  private LineageScrollResult getScrollResultInBatches(List<LineageRelationship> lineageRelationships,
      @Nonnull String input, @Nullable Filter inputFilters, @Nullable SortCriterion sortCriterion, @Nullable String scrollId,
      @Nonnull String keepAlive, int size, @Nonnull SearchFlags searchFlags) {
    LineageScrollResult finalResult =
        new LineageScrollResult().setEntities(new LineageSearchEntityArray(Collections.emptyList()))
            .setMetadata(new SearchResultMetadata().setAggregations(new AggregationMetadataArray()))
            .setPageSize(size)
            .setNumEntities(0);
    List<List<LineageRelationship>> batchedRelationships = Lists.partition(lineageRelationships, MAX_TERMS);
    int querySize = size;
    for (List<LineageRelationship> batch : batchedRelationships) {
      List<String> entitiesToQuery = batch.stream()
          .map(relationship -> relationship.getEntity().getEntityType())
          .distinct()
          .collect(Collectors.toList());
      Map<Urn, LineageRelationship> urnToRelationship = generateUrnToRelationshipMap(batch);
      Filter finalFilter = buildFilter(urnToRelationship.keySet(), inputFilters);

      LineageScrollResult resultForBatch = buildLineageScrollResult(
          _searchService.scrollAcrossEntities(entitiesToQuery, input, finalFilter, sortCriterion, scrollId, keepAlive, querySize,
              searchFlags), urnToRelationship);
      querySize = Math.max(0, size - resultForBatch.getEntities().size());
      finalResult = mergeScrollResult(finalResult, resultForBatch);
    }

    finalResult.getMetadata().getAggregations().add(0, DEGREE_FILTER_GROUP);
    return finalResult.setPageSize(size);
  }

  private LineageScrollResult buildLineageScrollResult(@Nonnull ScrollResult scrollResult,
      Map<Urn, LineageRelationship> urnToRelationship) {
    AggregationMetadataArray aggregations = new AggregationMetadataArray(scrollResult.getMetadata().getAggregations());
    LineageScrollResult lineageScrollResult = new LineageScrollResult().setEntities(new LineageSearchEntityArray(scrollResult.getEntities()
            .stream()
            .map(searchEntity -> buildLineageSearchEntity(searchEntity, urnToRelationship.get(searchEntity.getEntity())))
            .collect(Collectors.toList())))
        .setMetadata(new SearchResultMetadata().setAggregations(aggregations))
        .setPageSize(scrollResult.getPageSize())
        .setNumEntities(scrollResult.getNumEntities());

    if (scrollResult.getScrollId() != null) {
      lineageScrollResult.setScrollId(scrollResult.getScrollId());
    }
    return lineageScrollResult;
  }

  @SneakyThrows
  public static LineageScrollResult mergeScrollResult(LineageScrollResult one, LineageScrollResult two) {
    LineageScrollResult finalResult = one.clone();
    finalResult.getEntities().addAll(two.getEntities());
    finalResult.setNumEntities(one.getNumEntities() + two.getNumEntities());

    Map<String, AggregationMetadata> aggregations = one.getMetadata()
        .getAggregations()
        .stream()
        .collect(Collectors.toMap(AggregationMetadata::getName, Function.identity()));
    two.getMetadata().getAggregations().forEach(metadata -> {
      if (aggregations.containsKey(metadata.getName())) {
        aggregations.put(metadata.getName(), SearchUtils.merge(aggregations.get(metadata.getName()), metadata));
      } else {
        aggregations.put(metadata.getName(), metadata);
      }
    });
    finalResult.getMetadata().setAggregations(new AggregationMetadataArray(FilterUtils.rankFilterGroups(aggregations)));
    if (two.getScrollId() != null) {
      finalResult.setScrollId(two.getScrollId());
    }
    return finalResult;
  }
}
