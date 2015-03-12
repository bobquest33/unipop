package com.tinkerpop.gremlin.elastic.elasticservice;

import com.tinkerpop.gremlin.elastic.elasticservice.TimingAccessor.Timer;
import com.tinkerpop.gremlin.elastic.structure.*;
import com.tinkerpop.gremlin.structure.*;
import com.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.commons.configuration.Configuration;
import org.elasticsearch.action.get.*;
import org.elasticsearch.action.index.*;
import org.elasticsearch.action.search.*;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.*;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.index.query.*;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.*;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.SearchHit;

import java.io.IOException;
import java.util.*;
import java.util.stream.*;

public class ElasticService {

    //region members

    public static class ClientType {
        public static String TRANSPORT_CLIENT = "TRANSPORT_CLIENT";
        public static String NODE_CLIENT = "NODE_CLIENT";
        public static String NODE = "NODE";
    }

    private ElasticGraph graph;
    public SchemaProvider schemaProvider;
    private boolean refresh;
    private final String clusterName;
    private final String addresses;
    public Client client;
    private Node node;
    TimingAccessor timingAccessor = new TimingAccessor();

    //endregion

    //region initialization

    public ElasticService(ElasticGraph graph, Configuration configuration) throws IOException {
        timer("initialization").start();

        this.graph = graph;
        this.refresh = configuration.getBoolean("elasticsearch.refresh", true);
        this.clusterName = configuration.getString("elasticsearch.cluster.name", "elasticsearch");
        this.addresses = configuration.getString("elasticsearch.cluster.address", "127.0.0.1");
        String schemaProvider = configuration.getString("elasticsearch.schemaProvider", DefaultSchemaProvider.class.getCanonicalName());
        String clientType =configuration.getString("elasticsearch.client", ClientType.NODE);

        if (clientType.equals(ClientType.TRANSPORT_CLIENT)) createTransportClient();
        else if (clientType.equals(ClientType.NODE_CLIENT)) createNode(true);
        else if (clientType.equals(ClientType.NODE)) createNode(false);
        else throw new IllegalArgumentException("clientType unknown:" + clientType);

        try {
            Class c = Class.forName(schemaProvider);
            this.schemaProvider = (SchemaProvider) c.newInstance();
            this.schemaProvider.init(this.client, configuration);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to load SchemaProvider:" + schemaProvider, e);
        }

        timer("initialization").stop();
    }

    private void createTransportClient() {
        Settings settings = ImmutableSettings.settingsBuilder().put("cluster.name", clusterName).build();
        TransportClient transportClient = new TransportClient(settings);
        for(String address : addresses.split(","))
            transportClient.addTransportAddress(new InetSocketTransportAddress(address, 9300));
        this.client = transportClient;
    }

    private void createNode(boolean client) {
        this.node = NodeBuilder.nodeBuilder().client(client).clusterName(clusterName).build();
        node.start();
        this.client = node.client();
    }

    public void close() {
        client.close();
        schemaProvider.close();
        if (node != null) node.close();
        timingAccessor.print();
    }

    //endregion

    //region queries

    public IndexResponse addElement(String label, Object idValue, ElasticElement.Type type, Object... keyValues) {
        timer("add element").start();
        for (int i = 0; i < keyValues.length; i = i + 2) {
            String key = keyValues[i].toString();
            Object value = keyValues[i + 1];
            ElementHelper.validateProperty(key, value);
        }

        SchemaProvider.AddElementResult addElementResult = schemaProvider.addElement(label, idValue, type, keyValues);

        IndexRequestBuilder indexRequestBuilder;
        if (idValue == null) indexRequestBuilder = client.prepareIndex(addElementResult.getIndex(), label);
        else indexRequestBuilder = client.prepareIndex(addElementResult.getIndex(), label, idValue.toString());

        IndexResponse indexResponse = indexRequestBuilder.setCreate(true).setSource(addElementResult.getKeyValues()).execute().actionGet();
        timer("add element").stop();
        return indexResponse;
    }

    public void deleteElement(Element element) {
        timer("remove element").start();
        client.prepareDelete(schemaProvider.getIndex(element), element.label(), element.id().toString()).execute().actionGet();
        timer("remove element").stop();
    }

    public void deleteElements(Iterator<Element> elements) {
        elements.forEachRemaining(this::deleteElement);
    }

    public <V> void addProperty(Element element, String key, V value) {
        timer("update property").start();
        client.prepareUpdate(schemaProvider.getIndex(element), element.label(), element.id().toString())
                .setDoc(key, value).execute().actionGet();
        timer("update property").stop();
    }

    public void removeProperty(Element element, String key) {
        timer("remove property").start();
        client.prepareUpdate(schemaProvider.getIndex(element), element.label(), element.id().toString()).setScript("ctx._source.remove(\"" + key + "\")", ScriptService.ScriptType.INLINE).get();
        timer("remove property").stop();
    }

    public Iterator<Vertex> getVertices(Object... ids) {
        if (ids == null || ids.length == 0) return Collections.emptyIterator();

        MultiGetResponse responses = get(ids);
        ArrayList<Vertex> vertices = new ArrayList<>(ids.length);
        for (MultiGetItemResponse getResponse : responses) {
            GetResponse response = getResponse.getResponse();
            if (!response.isExists()) throw Graph.Exceptions.elementNotFound(Vertex.class, response.getId());
            vertices.add(createVertex(response.getId(), response.getType(), response.getSource()));
        }
        return vertices.iterator();
    }

    public Iterator<Edge> getEdges(Object... ids) {
        if (ids == null || ids.length == 0) return Collections.emptyIterator();

        MultiGetResponse responses = get(ids);
        ArrayList<Edge> edges = new ArrayList<>(ids.length);
        for (MultiGetItemResponse getResponse : responses) {
            GetResponse response = getResponse.getResponse();
            if (!response.isExists()) throw Graph.Exceptions.elementNotFound(Edge.class, response.getId());
            edges.add(createEdge(response.getId(), response.getType(), response.getSource()));
        }
        return edges.iterator();
    }

    public Iterator<Vertex> searchVertices(FilterBuilder filter, String... labels) {
        Stream<SearchHit> hits = search(filter, ElasticElement.Type.vertex, labels);
        return hits.map((hit) -> createVertex(hit.getId(), hit.getType(), hit.getSource())).iterator();
    }

    public Iterator<Edge> searchEdges(FilterBuilder filter, String... labels) {
        Stream<SearchHit> hits = search(filter, ElasticElement.Type.edge, labels);
        return hits.map((hit) -> createEdge(hit.getId(), hit.getType(), hit.getSource())).iterator();
    }

    private MultiGetResponse get(Object[] ids) {
        timer("get").start();
        MultiGetRequest request = new MultiGetRequest();
        for (Object id : ids)
            request.add(schemaProvider.getIndex(id), null, id.toString());
        MultiGetResponse multiGetItemResponses = client.multiGet(request).actionGet();
        timer("get").stop();
        return multiGetItemResponses;
    }

    private Stream<SearchHit> search(FilterBuilder filter, ElasticElement.Type type, String... labels) {
        timer("search").start();

        SchemaProvider.SearchResult result = schemaProvider.search(filter, type, labels);

        if (refresh) client.admin().indices().prepareRefresh(result.getIndices()).execute().actionGet();


        SearchRequestBuilder searchRequestBuilder = client.prepareSearch(result.getIndices())
                .setQuery(QueryBuilders.filteredQuery(QueryBuilders.matchAllQuery(),result.getFilter())).setFrom(0).setSize(100000); //TODO: retrive with scroll for efficiency
        if (labels != null && labels.length > 0 && labels[0] != null)
            searchRequestBuilder = searchRequestBuilder.setTypes(labels);

        SearchResponse searchResponse = searchRequestBuilder.execute().actionGet();
        Iterable<SearchHit> hitsIterable = () -> searchResponse.getHits().iterator();
        Stream<SearchHit> hitStream = StreamSupport.stream(hitsIterable.spliterator(), false);
        timer("search").stop();
        return hitStream;
    }


    private Vertex createVertex(Object id, String label, Map<String, Object> fields) {
        ElasticVertex vertex = new ElasticVertex(id, label, null, graph);
        fields.entrySet().forEach((field) -> vertex.addPropertyLocal(field.getKey(), field.getValue()));
        return vertex;
    }

    private Edge createEdge(Object id, String label, Map<String, Object> fields) {
        ElasticEdge edge = new ElasticEdge(id, label, fields.get(ElasticEdge.OutId), fields.get(ElasticEdge.InId), null, graph);
        fields.entrySet().forEach((field) -> edge.addPropertyLocal(field.getKey(), field.getValue()));
        return edge;
    }

    //endregion

    //region meta

    private Timer timer(String name) {
        return timingAccessor.timer(name);
    }

    public void collectData() {
        timingAccessor.print();
    }

    @Override
    public String toString() {
        return "ElasticService{" +
                "schema='" + schemaProvider + '\'' +
                ", client=" + client +
                '}';
    }
    //endregion
}
