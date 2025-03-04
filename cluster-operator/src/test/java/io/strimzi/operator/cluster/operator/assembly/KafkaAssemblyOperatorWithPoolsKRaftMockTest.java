/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.StrimziPodSet;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolBuilder;
import io.strimzi.api.kafka.model.nodepool.ProcessRoles;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageBuilder;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.KafkaVersionTestUtils;
import io.strimzi.operator.cluster.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.cluster.model.KafkaCluster;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.model.PodSetUtils;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.model.PasswordGenerator;
import io.strimzi.operator.common.operator.MockCertManager;
import io.strimzi.platform.KubernetesVersion;
import io.strimzi.test.mockkube2.MockKube2;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

@EnableKubernetesMockClient(crud = true)
@ExtendWith(VertxExtension.class)
@SuppressWarnings("checkstyle:ClassFanOutComplexity")
public class KafkaAssemblyOperatorWithPoolsKRaftMockTest {
    private static final String NAMESPACE = "my-namespace";
    private static final String CLUSTER_NAME = "my-cluster";

    private static final KafkaVersion.Lookup VERSIONS = KafkaVersionTestUtils.getKafkaVersionLookup();

    private Vertx vertx;
    private WorkerExecutor sharedWorkerExecutor;
    // Injected by Fabric8 Mock Kubernetes Server
    @SuppressWarnings("unused")
    private KubernetesClient client;
    private MockKube2 mockKube;
    private ResourceOperatorSupplier supplier;
    private StrimziPodSetController podSetController;
    private KafkaAssemblyOperator operator;

    @BeforeEach
    public void init() {
        vertx = Vertx.vertx();
        sharedWorkerExecutor = vertx.createSharedWorkerExecutor("kubernetes-ops-pool");

        Kafka cluster = new KafkaBuilder()
                .withNewMetadata()
                    .withName(CLUSTER_NAME)
                    .withNamespace(NAMESPACE)
                    .withAnnotations(Map.of(Annotations.ANNO_STRIMZI_IO_NODE_POOLS, "enabled"))
                .endMetadata()
                .withNewSpec()
                    .withNewKafka()
                        .withConfig(new HashMap<>())
                        .withReplicas(3)
                        .withListeners(new GenericKafkaListenerBuilder()
                                .withName("tls")
                                .withPort(9092)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(true)
                                .build())
                        .withNewPersistentClaimStorage()
                            .withSize("123")
                            .withStorageClass("foo")
                            .withDeleteClaim(true)
                        .endPersistentClaimStorage()
                    .endKafka()
                    .withNewZookeeper()
                        .withReplicas(3)
                        .withNewPersistentClaimStorage()
                            .withSize("123")
                            .withStorageClass("foo")
                            .withDeleteClaim(true)
                        .endPersistentClaimStorage()
                    .endZookeeper()
                .endSpec()
                .withNewStatus()
                .withClusterId("CLUSTERID") // Needed to avoid CLuster ID conflicts => should be the same as used in the Kafka Admin API
                .endStatus()
                .build();

        KafkaNodePool poolA = new KafkaNodePoolBuilder()
                .withNewMetadata()
                    .withName("controllers")
                    .withNamespace(NAMESPACE)
                    .withLabels(Map.of(Labels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
                    .withGeneration(1L)
                .endMetadata()
                .withNewSpec()
                    .withReplicas(3)
                    .withNewJbodStorage()
                        .withVolumes(new PersistentClaimStorageBuilder().withId(0).withSize("100Gi").withStorageClass("gp99").build())
                    .endJbodStorage()
                    .withRoles(ProcessRoles.CONTROLLER)
                    .withResources(new ResourceRequirementsBuilder().withRequests(Map.of("cpu", new Quantity("4"))).build())
                .endSpec()
                .build();

        KafkaNodePool poolB = new KafkaNodePoolBuilder()
                .withNewMetadata()
                    .withName("brokers")
                    .withNamespace(NAMESPACE)
                    .withLabels(Map.of(Labels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
                    .withGeneration(1L)
                .endMetadata()
                .withNewSpec()
                    .withReplicas(3)
                    .withNewJbodStorage()
                        .withVolumes(new PersistentClaimStorageBuilder().withId(0).withSize("200Gi").withStorageClass("gp99").build())
                    .endJbodStorage()
                    .withRoles(ProcessRoles.BROKER)
                    .withResources(new ResourceRequirementsBuilder().withRequests(Map.of("cpu", new Quantity("6"))).build())
                .endSpec()
                .build();

        // Configure the Kubernetes Mock
        mockKube = new MockKube2.MockKube2Builder(client)
                .withKafkaNodePoolCrd()
                .withInitialKafkaNodePools(poolA, poolB)
                .withKafkaCrd()
                .withInitialKafkas(cluster)
                .withStrimziPodSetCrd()
                .withDeploymentController()
                .withPodController()
                .withServiceController()
                .build();
        mockKube.start();

        // We have to update the status to store the Kafka Cluster ID in it.
        // This is needed to keep the resources in sync with the Kafka Admin API mocks.
        Crds.kafkaOperation(client).resource(cluster).updateStatus();

        PlatformFeaturesAvailability pfa = new PlatformFeaturesAvailability(false, KubernetesVersion.MINIMAL_SUPPORTED_VERSION);
        supplier = supplierWithMocks();
        podSetController = new StrimziPodSetController(NAMESPACE, Labels.EMPTY, supplier.kafkaOperator, supplier.connectOperator, supplier.mirrorMaker2Operator, supplier.strimziPodSetOperator, supplier.podOperations, supplier.metricsProvider, Integer.parseInt(ClusterOperatorConfig.POD_SET_CONTROLLER_WORK_QUEUE_SIZE.defaultValue()));
        podSetController.start();

        ClusterOperatorConfig config = new ClusterOperatorConfig.ClusterOperatorConfigBuilder(ResourceUtils.dummyClusterOperatorConfig(), VERSIONS)
                .with(ClusterOperatorConfig.OPERATION_TIMEOUT_MS.key(), "10000")
                .with(ClusterOperatorConfig.FEATURE_GATES.key(), "+KafkaNodePools,+UseKRaft")
                .build();
        operator = new KafkaAssemblyOperator(vertx, pfa, new MockCertManager(),
                new PasswordGenerator(10, "a", "a"), supplier, config);
    }

    private ResourceOperatorSupplier supplierWithMocks() {
        return new ResourceOperatorSupplier(vertx, client, ResourceUtils.zookeeperLeaderFinder(vertx, client),
                ResourceUtils.adminClientProvider(), ResourceUtils.zookeeperScalerProvider(),
                ResourceUtils.metricsProvider(), new PlatformFeaturesAvailability(false, KubernetesVersion.MINIMAL_SUPPORTED_VERSION), 2_000);
    }

    @AfterEach
    public void afterEach() {
        podSetController.stop();
        mockKube.stop();
        sharedWorkerExecutor.close();
        vertx.close();
        ResourceUtils.cleanUpTemporaryTLSFiles();
    }

    /**
     * Tests how the KRaft controller-only nodes have their configuration changes tracked using a Pod annotations. The
     * annotation on controller-only pods should change when the controller-relevant config is changed. On broker pods
     * it should never change. To test this, the test does 3 reconciliations:
     *     - First initial one to establish the pods and collects the annotations
     *     - Second with change that is not relevant to controllers => annotations should be the same for all nodes as
     *       before
     *     - Third with change to a controller-relevant option => annotations for controller nodes should change, for
     *       broker nodes should be the same
     *
     * @param context   Test context
     */
    @Test
    public void testReconcileWithControllerRelevantConfigChange(VertxTestContext context) {
        Checkpoint async = context.checkpoint();

        Map<String, String> brokerConfigurationAnnos = new HashMap<>();

        operator.reconcile(new Reconciliation("initial-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    // Collect the configuration annotations
                    StrimziPodSet spsControllers = supplier.strimziPodSetOperator.client().inNamespace(NAMESPACE).withName(CLUSTER_NAME + "-controllers").get();
                    assertThat(spsControllers, is(notNullValue()));

                    spsControllers.getSpec().getPods().stream().map(PodSetUtils::mapToPod).forEach(pod -> {
                        brokerConfigurationAnnos.put(pod.getMetadata().getName(), pod.getMetadata().getAnnotations().get(KafkaCluster.ANNO_STRIMZI_BROKER_CONFIGURATION_HASH));
                    });

                    StrimziPodSet spsBrokers = supplier.strimziPodSetOperator.client().inNamespace(NAMESPACE).withName(CLUSTER_NAME + "-brokers").get();
                    assertThat(spsBrokers, is(notNullValue()));

                    spsBrokers.getSpec().getPods().stream().map(PodSetUtils::mapToPod).forEach(pod -> {
                        brokerConfigurationAnnos.put(pod.getMetadata().getName(), pod.getMetadata().getAnnotations().get(KafkaCluster.ANNO_STRIMZI_BROKER_CONFIGURATION_HASH));
                    });

                    // Update Kafka with dynamically changeable option that is not controller relevant => controller pod annotations should not change
                    Crds.kafkaOperation(client).inNamespace(NAMESPACE).withName(CLUSTER_NAME)
                            .edit(k -> new KafkaBuilder(k).editSpec().editKafka().addToConfig(Map.of("compression.type", "gzip")).endKafka().endSpec().build());
                })))
                .compose(v -> operator.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME)))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    StrimziPodSet spsControllers = supplier.strimziPodSetOperator.client().inNamespace(NAMESPACE).withName(CLUSTER_NAME + "-controllers").get();
                    assertThat(spsControllers, is(notNullValue()));

                    spsControllers.getSpec().getPods().stream().map(PodSetUtils::mapToPod).forEach(pod -> {
                        // Controller annotations should differ
                        assertThat(pod.getMetadata().getAnnotations().get(KafkaCluster.ANNO_STRIMZI_BROKER_CONFIGURATION_HASH), is(brokerConfigurationAnnos.get(pod.getMetadata().getName())));
                    });

                    StrimziPodSet spsBrokers = supplier.strimziPodSetOperator.client().inNamespace(NAMESPACE).withName(CLUSTER_NAME + "-brokers").get();
                    assertThat(spsBrokers, is(notNullValue()));

                    spsBrokers.getSpec().getPods().stream().map(PodSetUtils::mapToPod).forEach(pod -> {
                        // Broker annotations should be the same
                        assertThat(pod.getMetadata().getAnnotations().get(KafkaCluster.ANNO_STRIMZI_BROKER_CONFIGURATION_HASH), is(brokerConfigurationAnnos.get(pod.getMetadata().getName())));
                    });

                    // Update Kafka with dynamically changeable controller relevant option => controller pod annotations should change
                    Crds.kafkaOperation(client).inNamespace(NAMESPACE).withName(CLUSTER_NAME)
                            .edit(k -> new KafkaBuilder(k).editSpec().editKafka().addToConfig(Map.of("max.connections", "1000")).endKafka().endSpec().build());
                })))
                .compose(v -> operator.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME)))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    StrimziPodSet spsControllers = supplier.strimziPodSetOperator.client().inNamespace(NAMESPACE).withName(CLUSTER_NAME + "-controllers").get();
                    assertThat(spsControllers, is(notNullValue()));

                    spsControllers.getSpec().getPods().stream().map(PodSetUtils::mapToPod).forEach(pod -> {
                        // Controller annotations should differ
                        assertThat(pod.getMetadata().getAnnotations().get(KafkaCluster.ANNO_STRIMZI_BROKER_CONFIGURATION_HASH), is(not(brokerConfigurationAnnos.get(pod.getMetadata().getName()))));
                    });

                    StrimziPodSet spsBrokers = supplier.strimziPodSetOperator.client().inNamespace(NAMESPACE).withName(CLUSTER_NAME + "-brokers").get();
                    assertThat(spsBrokers, is(notNullValue()));

                    spsBrokers.getSpec().getPods().stream().map(PodSetUtils::mapToPod).forEach(pod -> {
                        // Broker annotations should be the same
                        assertThat(pod.getMetadata().getAnnotations().get(KafkaCluster.ANNO_STRIMZI_BROKER_CONFIGURATION_HASH), is(brokerConfigurationAnnos.get(pod.getMetadata().getName())));
                    });

                    async.flag();
                })));
    }
}
