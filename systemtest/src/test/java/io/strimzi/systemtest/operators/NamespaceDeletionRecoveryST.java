/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.operators;

import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.storage.StorageClass;
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder;
import io.strimzi.api.kafka.model.EntityOperatorSpecBuilder;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.systemtest.AbstractST;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.Environment;
import io.strimzi.systemtest.annotations.KRaftWithoutUTONotSupported;
import io.strimzi.systemtest.annotations.UTONotSupported;
import io.strimzi.systemtest.cli.KafkaCmdClient;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaClients;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaClientsBuilder;
import io.strimzi.systemtest.resources.operator.SetupClusterOperator;
import io.strimzi.systemtest.annotations.IsolatedTest;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.resources.crd.KafkaTopicResource;
import io.strimzi.systemtest.storage.TestStorage;
import io.strimzi.systemtest.templates.crd.KafkaTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTopicTemplates;
import io.strimzi.systemtest.templates.specific.ScraperTemplates;
import io.strimzi.systemtest.utils.ClientUtils;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaTopicUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.DeploymentUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.NamespaceUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.PersistentVolumeClaimUtils;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.List;

import static io.strimzi.systemtest.Constants.INTERNAL_CLIENTS_USED;
import static io.strimzi.systemtest.Constants.RECOVERY;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

/**
 * Suite for testing topic recovery in case of namespace deletion.
 * Procedure described in documentation  https://strimzi.io/docs/master/#namespace-deletion_str
 * Note: Suite can be run on minikube only with previously created PVs and StorageClass using local provisioner.
 * Reason why this test class is not part of regression:
 * These tests does not have to be run every time with PRs and so on, the nature of the tests is sufficient for recovery profile only.
 */
@Tag(RECOVERY)
@KRaftWithoutUTONotSupported
class NamespaceDeletionRecoveryST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(NamespaceDeletionRecoveryST.class);
    private String storageClassName = "retain";

    /**
     * In case that we have all KafkaTopic resources that existed before cluster loss, including internal topics,
     * we can simply recreate all KafkaTopic resources and then deploy the Kafka cluster.
     * At the end we verify that we can receive messages from topic (so data are present).
     */
    @IsolatedTest("We need for each test case its own Cluster Operator")
    @Tag(INTERNAL_CLIENTS_USED)
    @UTONotSupported("https://github.com/strimzi/strimzi-kafka-operator/issues/8864")
    void testTopicAvailable(ExtensionContext extensionContext) {
        final TestStorage testStorage = new TestStorage(extensionContext, Environment.TEST_SUITE_NAMESPACE);

        prepareEnvironmentForRecovery(extensionContext, testStorage);

        // Wait till consumer offset topic is created
        KafkaTopicUtils.waitForKafkaTopicCreationByNamePrefix(testStorage.getNamespaceName(), "consumer-offsets");
        // Get list of topics and list of PVC needed for recovery
        List<KafkaTopic> kafkaTopicList = KafkaTopicResource.kafkaTopicClient().inNamespace(testStorage.getNamespaceName()).list().getItems();
        List<PersistentVolumeClaim> persistentVolumeClaimList = kubeClient().getClient().persistentVolumeClaims().list().getItems();
        deleteAndRecreateNamespace(testStorage.getNamespaceName());

        recreatePvcAndUpdatePv(persistentVolumeClaimList, testStorage.getNamespaceName());
        recreateClusterOperator(extensionContext, testStorage.getNamespaceName());

        // Recreate all KafkaTopic resources
        for (KafkaTopic kafkaTopic : kafkaTopicList) {
            kafkaTopic.getMetadata().setResourceVersion(null);
            KafkaTopicResource.kafkaTopicClient().inNamespace(testStorage.getNamespaceName()).resource(kafkaTopic).create();
        }

        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaPersistent(testStorage.getClusterName(), 3, 3)
            .editMetadata()
                .withNamespace(testStorage.getNamespaceName())
            .endMetadata()
            .editSpec()
                .editKafka()
                    .withNewPersistentClaimStorage()
                        .withSize("1Gi")
                    .endPersistentClaimStorage()
                .endKafka()
                .editZookeeper()
                    .withNewPersistentClaimStorage()
                        .withSize("1Gi")
                    .endPersistentClaimStorage()
                .endZookeeper()
            .endSpec()
            .build());

        verifyStabilityBySendingAndReceivingMessages(extensionContext, testStorage);
    }

    /**
     * In case we don't have KafkaTopic resources from before the cluster loss, we do these steps:
     *  1. deploy the Kafka cluster without Topic Operator - otherwise topics will be deleted
     *  2. delete KafkaTopic Store topics - `__strimzi-topic-operator-kstreams-topic-store-changelog` and `__strimzi_store_topic`
     *  3. enable Topic Operator by redeploying Kafka cluster
     * @throws InterruptedException - sleep
     */
    @IsolatedTest("We need for each test case its own Cluster Operator")
    @Tag(INTERNAL_CLIENTS_USED)
    @UTONotSupported("https://github.com/strimzi/strimzi-kafka-operator/issues/8864")
    void testTopicNotAvailable(ExtensionContext extensionContext) {
        final TestStorage testStorage = new TestStorage(extensionContext, Environment.TEST_SUITE_NAMESPACE);

        final List<String> topicsToRemove = List.of("__strimzi-topic-operator-kstreams-topic-store-changelog", "__strimzi_store_topic");

        prepareEnvironmentForRecovery(extensionContext, testStorage);

        // Wait till consumer offset topic is created
        KafkaTopicUtils.waitForKafkaTopicCreationByNamePrefix(testStorage.getNamespaceName(), "consumer-offsets");

        // Get list of topics and list of PVC needed for recovery
        List<PersistentVolumeClaim> persistentVolumeClaimList = kubeClient().listPersistentVolumeClaims(testStorage.getNamespaceName(), testStorage.getClusterName());

        LOGGER.info("List of PVCs inside Namespace: {}", testStorage.getNamespaceName());
        for (PersistentVolumeClaim pvc : persistentVolumeClaimList) {
            PersistentVolume pv = kubeClient().getPersistentVolumeWithName(pvc.getSpec().getVolumeName());
            LOGGER.info("Claim: {} has bounded Volume: {}", pvc.getMetadata().getName(), pv.getMetadata().getName());
        }

        String kafkaPodName = kubeClient().listPodsByPrefixInName(testStorage.getNamespaceName(), testStorage.getKafkaStatefulSetName()).get(0).getMetadata().getName();

        LOGGER.info("Currently present Topics inside Kafka: {}/{} are: {}", testStorage.getNamespaceName(), kafkaPodName,
            KafkaCmdClient.listTopicsUsingPodCli(testStorage.getNamespaceName(), kafkaPodName, KafkaResources.plainBootstrapAddress(testStorage.getClusterName())));

        LOGGER.info("Waiting for correct Topics to be present inside Kafka");
        for (String topicName : topicsToRemove) {
            KafkaTopicUtils.waitForTopicWillBePresentInKafka(testStorage.getNamespaceName(), topicName, KafkaResources.plainBootstrapAddress(testStorage.getClusterName()), kafkaPodName);
        }

        LOGGER.info("Deleting namespace and recreating for recovery");
        deleteAndRecreateNamespace(testStorage.getNamespaceName());

        LOGGER.info("Recreating PVCs and updating PVs for recovery");
        recreatePvcAndUpdatePv(persistentVolumeClaimList, testStorage.getNamespaceName());

        LOGGER.info("Recreating Cluster Operator");
        recreateClusterOperator(extensionContext, testStorage.getNamespaceName());

        LOGGER.info("Recreating Kafka cluster without Topic Operator");
        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaPersistent(testStorage.getClusterName(), 3, 3)
            .editMetadata()
                .withNamespace(testStorage.getNamespaceName())
            .endMetadata()
            .editSpec()
                .withNewEntityOperator()
                .endEntityOperator()
            .endSpec()
            .build(),
            ScraperTemplates.scraperPod(testStorage.getNamespaceName(), testStorage.getScraperName()).build()
        );

        String scraperPodName = kubeClient().listPodsByPrefixInName(testStorage.getNamespaceName(), testStorage.getScraperName()).get(0).getMetadata().getName();

        LOGGER.info("Currently present Topics inside Kafka: {}/{} are: {}", testStorage.getNamespaceName(), kafkaPodName,
            KafkaCmdClient.listTopicsUsingPodCli(testStorage.getNamespaceName(), kafkaPodName, KafkaResources.plainBootstrapAddress(testStorage.getClusterName())));

        LOGGER.info("Removing store Topics before deploying Topic Operator");
        // Remove all topic data from topic store and wait for the deletion -> must do before deploying topic operator
        for (String topicName : topicsToRemove) {
            // First check topic is present in kafka
            KafkaTopicUtils.waitForTopicWillBePresentInKafka(testStorage.getNamespaceName(), topicName, KafkaResources.plainBootstrapAddress(testStorage.getClusterName()), scraperPodName);
            // Then delete it using Pod cli
            KafkaCmdClient.deleteTopicUsingPodCli(testStorage.getNamespaceName(), scraperPodName, KafkaResources.plainBootstrapAddress(testStorage.getClusterName()), topicName);
            // Wait for it to be deleted
            KafkaTopicUtils.waitForTopicsByPrefixDeletionUsingPodCli(testStorage.getNamespaceName(), topicName, KafkaResources.plainBootstrapAddress(testStorage.getClusterName()), scraperPodName, "");
        }

        LOGGER.info("Adding Topic Operator to existing Kafka");
        KafkaResource.replaceKafkaResourceInSpecificNamespace(testStorage.getClusterName(), k -> {
            k.getSpec().setEntityOperator(new EntityOperatorSpecBuilder()
                .withNewTopicOperator()
                .endTopicOperator()
                .withNewUserOperator()
                .endUserOperator().build());
        }, testStorage.getNamespaceName());

        DeploymentUtils.waitForDeploymentAndPodsReady(testStorage.getNamespaceName(), testStorage.getEoDeploymentName(), 1);

        verifyStabilityBySendingAndReceivingMessages(extensionContext, testStorage);
    }

    private void prepareEnvironmentForRecovery(ExtensionContext extensionContext, TestStorage testStorage) {
        LOGGER.info("####################################");
        LOGGER.info("Creating environment for recovery");
        LOGGER.info("####################################");
        clusterOperator = new SetupClusterOperator.SetupClusterOperatorBuilder()
            .withExtensionContext(extensionContext)
            .withNamespace(testStorage.getNamespaceName())
            .createInstallation()
            .runInstallation();

        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaPersistent(testStorage.getClusterName(), 3, 3)
            .editMetadata()
                .withNamespace(testStorage.getNamespaceName())
            .endMetadata()
            .editSpec()
                .editKafka()
                    .withNewPersistentClaimStorage()
                        .withSize("1Gi")
                        .withStorageClass(storageClassName)
                    .endPersistentClaimStorage()
                .endKafka()
                .editZookeeper()
                    .withNewPersistentClaimStorage()
                        .withSize("1Gi")
                        .withStorageClass(storageClassName)
                    .endPersistentClaimStorage()
                .endZookeeper()
            .endSpec()
            .build());

        resourceManager.createResourceWithWait(extensionContext, KafkaTopicTemplates.topic(testStorage).build());

        KafkaClients clients = new KafkaClientsBuilder()
            .withProducerName(testStorage.getProducerName())
            .withConsumerName(testStorage.getConsumerName())
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(testStorage.getClusterName()))
            .withNamespaceName(testStorage.getNamespaceName())
            .withTopicName(testStorage.getTopicName())
            .withMessageCount(testStorage.getMessageCount())
            .build();

        resourceManager.createResourceWithWait(extensionContext, clients.producerStrimzi(), clients.consumerStrimzi());
        ClientUtils.waitForClientsSuccess(testStorage);

        LOGGER.info("##################################################");
        LOGGER.info("Environment for recovery was successfully created");
        LOGGER.info("##################################################");
    }

    private void verifyStabilityBySendingAndReceivingMessages(ExtensionContext extensionContext, TestStorage testStorage) {
        KafkaClients clients = new KafkaClientsBuilder()
            .withProducerName(testStorage.getProducerName())
            .withConsumerName(testStorage.getConsumerName())
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(testStorage.getClusterName()))
            .withNamespaceName(testStorage.getNamespaceName())
            .withTopicName(testStorage.getTopicName())
            .withMessageCount(testStorage.getMessageCount())
            .build();

        resourceManager.createResourceWithWait(extensionContext, clients.producerStrimzi(), clients.consumerStrimzi());
        ClientUtils.waitForClientsSuccess(testStorage);
    }

    private void recreatePvcAndUpdatePv(List<PersistentVolumeClaim> persistentVolumeClaimList, String namespace) {
        for (PersistentVolumeClaim pvc : persistentVolumeClaimList) {
            pvc.getMetadata().setResourceVersion(null);
            kubeClient().createPersistentVolumeClaim(namespace, pvc);

            PersistentVolume pv = kubeClient().getPersistentVolumeWithName(pvc.getSpec().getVolumeName());
            pv.getSpec().setClaimRef(null);
            kubeClient().updatePersistentVolume(pv);

            PersistentVolumeClaimUtils.waitForPersistentVolumeClaimPhase(pv.getMetadata().getName(), Constants.PVC_PHASE_BOUND);
        }
    }

    private void recreateClusterOperator(ExtensionContext extensionContext, String namespace) {
        clusterOperator = new SetupClusterOperator.SetupClusterOperatorBuilder()
            .withExtensionContext(extensionContext)
            .withNamespace(namespace)
            .createInstallation()
            .runInstallation();
    }

    private void deleteAndRecreateNamespace(String namespace) {
        // Delete namespace with all resources
        kubeClient().deleteNamespace(namespace);
        NamespaceUtils.waitForNamespaceDeletion(namespace);

        // Recreate namespace
        cluster.createNamespace(namespace);
        StUtils.copyImagePullSecrets(namespace);
    }

    @BeforeAll
    void createStorageClass() {
        // Delete specific StorageClass if present from previous
        kubeClient().getClient().storage().v1().storageClasses().withName(storageClassName).delete();

        // Get default StorageClass and change reclaim policy
        StorageClass defaultStorageClass =  kubeClient().getClient().storage().v1().storageClasses().list().getItems().stream().filter(sg -> {
            Map<String, String> annotations = sg.getMetadata().getAnnotations();
            return annotations.get("storageclass.kubernetes.io/is-default-class").equals("true");
        }).findFirst().get();

        StorageClass retainStorageClass = new StorageClassBuilder(defaultStorageClass)
            .withNewMetadata()
                .withName(storageClassName)
            .endMetadata()
            .withReclaimPolicy("Retain")
            .withVolumeBindingMode("WaitForFirstConsumer")
            .build();

        kubeClient().createStorageClass(retainStorageClass);
    }

    @AfterAll
    void teardown() {
        kubeClient().deleteStorageClassWithName(storageClassName);

        kubeClient().getClient().persistentVolumes().list().getItems().stream()
            .filter(pv -> pv.getSpec().getClaimRef().getName().contains("kafka") || pv.getSpec().getClaimRef().getName().contains("zookeeper"))
            .forEach(pv -> kubeClient().getClient().persistentVolumes().resource(pv).delete());
    }
}
