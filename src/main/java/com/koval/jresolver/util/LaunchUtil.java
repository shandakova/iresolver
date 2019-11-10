package com.koval.jresolver.util;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.koval.jresolver.common.api.doc2vec.VectorModel;
import com.koval.jresolver.common.api.doc2vec.VectorModelCreator;
import com.koval.jresolver.common.api.doc2vec.VectorModelSerializer;
import com.koval.jresolver.common.api.util.TextUtil;
import com.koval.jresolver.docprocessor.DocumentationProcessor;
import com.koval.jresolver.docprocessor.configuration.DocumentationProcessorProperties;
import com.koval.jresolver.docprocessor.core.DocTypeDetector;
import com.koval.jresolver.docprocessor.core.FileParser;
import org.apache.commons.io.FileUtils;
import org.apache.tika.mime.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.koval.jresolver.common.api.ProcessExecutor;
import com.koval.jresolver.common.api.auth.Credentials;
import com.koval.jresolver.common.api.auth.CredentialsKeeper;
import com.koval.jresolver.common.api.auth.CredentialsProtector;
import com.koval.jresolver.common.api.bean.issue.IssueField;
import com.koval.jresolver.common.api.bean.result.IssueAnalysingResult;
import com.koval.jresolver.common.api.component.connector.Connector;
import com.koval.jresolver.common.api.component.connector.IssueClient;
import com.koval.jresolver.common.api.component.connector.IssueReceiver;
import com.koval.jresolver.common.api.component.processor.IssueProcessor;
import com.koval.jresolver.common.api.component.reporter.ReportGenerator;
import com.koval.jresolver.configuration.ConnectorConstants;
import com.koval.jresolver.configuration.ControlProperties;
import com.koval.jresolver.configuration.ProcessorConstants;
import com.koval.jresolver.configuration.ReporterConstants;
import com.koval.jresolver.connector.bugzilla.BugZillaConnector;
import com.koval.jresolver.connector.bugzilla.client.BugZillaIssueClientFactory;
import com.koval.jresolver.connector.bugzilla.configuration.BugZillaConnectorProperties;
import com.koval.jresolver.connector.bugzilla.exception.BugZillaConnectorException;
import com.koval.jresolver.connector.jira.JiraConnector;
import com.koval.jresolver.connector.jira.client.JiraIssueClientFactory;
import com.koval.jresolver.connector.jira.configuration.JiraConnectorProperties;
import com.koval.jresolver.connector.jira.exception.JiraConnectorException;
import com.koval.jresolver.exception.ResolverException;
import com.koval.jresolver.processor.link.LinkProcessor;
import com.koval.jresolver.processor.link.configuration.LinkProcessorProperties;
import com.koval.jresolver.processor.rules.RuleEngineProcessor;
import com.koval.jresolver.processor.rules.core.RuleEngine;
import com.koval.jresolver.processor.rules.core.impl.DroolsRuleEngine;
import com.koval.jresolver.processor.similarity.SimilarityProcessor;
import com.koval.jresolver.processor.similarity.configuration.SimilarityProcessorProperties;
import com.koval.jresolver.processor.similarity.core.DataSetCreator;
import com.koval.jresolver.processor.similarity.test.TestSimilarityProcessor;
import com.koval.jresolver.reporter.html.HtmlReportGenerator;
import com.koval.jresolver.reporter.html.configuration.HtmlReporterConfiguration;
import com.koval.jresolver.reporter.text.TextReportGenerator;


public final class LaunchUtil {

  private static final Logger LOGGER = LoggerFactory.getLogger(LaunchUtil.class);

  private LaunchUtil() {
  }

  public static void createDataSet() {
    ControlProperties controlProperties = new ControlProperties();
    SimilarityProcessorProperties similarityProcessorProperties = new SimilarityProcessorProperties();
    try (IssueClient issueClient = getIssueClient(controlProperties)) {
      Connector connector = getConnector(controlProperties, issueClient);
      IssueReceiver receiver = connector.getResolvedIssuesReceiver();
      DataSetCreator dataSetCreator = new DataSetCreator(receiver, similarityProcessorProperties);
      dataSetCreator.create();
    } catch (IOException e) {
      LOGGER.error("Could not create data set file.", e);
    }
  }

  public static void createVectorModel() {
    SimilarityProcessorProperties similarityProcessorProperties = new SimilarityProcessorProperties();
    VectorModelCreator vectorModelCreator = new VectorModelCreator(similarityProcessorProperties);
    File dataSetFile = new File(similarityProcessorProperties.getWorkFolder(), similarityProcessorProperties.getDataSetFileName());
    try {
      VectorModel vectorModel = vectorModelCreator.createFromFile(dataSetFile);
      VectorModelSerializer vectorModelSerializer = new VectorModelSerializer(similarityProcessorProperties);
      vectorModelSerializer.serialize(vectorModel);
    } catch (IOException e) {
      LOGGER.error("Could not create vector model file.", e);
    }
  }

  public static void createDocumentationDataSet() {
    DocumentationProcessorProperties properties = new DocumentationProcessorProperties();
    DocTypeDetector docTypeDetector = new DocTypeDetector();
    File docsFolder = new File(properties.getDocsFolder());

    int pageIndex = 0;
    int documentIndex = 0;

    for (final File docFile : Objects.requireNonNull(docsFolder.listFiles())) {
      if (docFile.isFile()) {
        try (InputStream inputFileStream = new BufferedInputStream(new FileInputStream(docFile))) {
          MediaType mediaType = docTypeDetector.detectType(inputFileStream, docFile.getName());
          if (docTypeDetector.isTypeSupported(mediaType)) {

            try (PrintWriter dataSetOutput = new PrintWriter(new File(properties.getWorkFolder(), properties.getDataSetFileName()), StandardCharsets.UTF_8.name());
                 PrintWriter metadataOutput = new PrintWriter(new File(properties.getWorkFolder(), "doc-metadata.txt"), StandardCharsets.UTF_8.name());
                 PrintWriter docListOutput = new PrintWriter(new File(properties.getWorkFolder(), "doc-list.txt"), StandardCharsets.UTF_8.name())) {

              FileParser fileParser = docTypeDetector.getFileParser(mediaType);
              Map<Integer, String> result = fileParser.getMapping(inputFileStream);

              for (Map.Entry<Integer, String> docPage : result.entrySet()) {
                String docPageKey = "doc_" + pageIndex;
                pageIndex++;

                dataSetOutput.print(docPageKey);
                dataSetOutput.print("|");
                dataSetOutput.println(TextUtil.simplify(docPage.getValue()));

                metadataOutput.print(docPageKey);
                metadataOutput.print("|");
                metadataOutput.print(documentIndex);
                metadataOutput.print("|");
                metadataOutput.println(docPage.getKey());
              }

              docListOutput.print(documentIndex);
              docListOutput.print(" ");
              docListOutput.println(docFile.getName());
              documentIndex++;

            } catch (FileNotFoundException | UnsupportedEncodingException e) {
              LOGGER.error("Could not write to output file", e);
            }

          }
        } catch (FileNotFoundException e) {
          LOGGER.error("Could not find documentation file: " + docFile.getAbsolutePath(), e);
        } catch (IOException e) {
          LOGGER.error("Could not read documentation file" + docFile.getAbsolutePath(), e);
        }
      }
    }
  }

  public static void createDocumentationVectorModel() {
    DocumentationProcessorProperties properties = new DocumentationProcessorProperties();
    VectorModelCreator vectorModelCreator = new VectorModelCreator(properties);
    File dataSetFile = new File(properties.getWorkFolder(), properties.getDataSetFileName());
    try {
      VectorModel vectorModel = vectorModelCreator.createFromFile(dataSetFile);
      VectorModelSerializer vectorModelSerializer = new VectorModelSerializer(properties);
      vectorModelSerializer.serialize(vectorModel);
    } catch (IOException e) {
      LOGGER.error("Could not create documentation vector model file.", e);
    }
  }

  public static void clean() {
    SimilarityProcessorProperties similarityProcessorProperties = new SimilarityProcessorProperties();
    try {
      File folder = new File(similarityProcessorProperties.getWorkFolder());
      if (folder.exists()) {
        FileUtils.cleanDirectory(new File(similarityProcessorProperties.getWorkFolder()));
        LOGGER.info("Folder {} was cleaned", similarityProcessorProperties.getWorkFolder());
      } else {
        LOGGER.warn("Folder {} does not exist", similarityProcessorProperties.getWorkFolder());
      }
    } catch (IOException e) {
      LOGGER.error("Could not clean folder: " + similarityProcessorProperties.getWorkFolder(), e);
    }
  }

  public static void run() {
    ControlProperties controlProperties = new ControlProperties();
    List<IssueAnalysingResult> results = new ArrayList<>();
    try (IssueClient issueClient = getIssueClient(controlProperties);
         RuleEngine ruleEngine = new DroolsRuleEngine()) {
      Connector connector = getConnector(controlProperties, issueClient);
      IssueReceiver receiver = connector.getUnresolvedIssuesReceiver();
      ProcessExecutor executor = new ProcessExecutor();
      for (IssueProcessor issueProcessor : getIssueProcessors(controlProperties, issueClient, ruleEngine)) {
        executor.add(issueProcessor);
      }
      if (controlProperties.isParallel()) {
        while (receiver.hasNextIssues()) {
          results.addAll(executor.parallelExecute(receiver.getNextIssues()));
        }
      } else {
        while (receiver.hasNextIssues()) {
          results.addAll(executor.execute(receiver.getNextIssues()));
        }
      }
    } catch (IOException e) {
      LOGGER.error("Could not run issues processing.", e);
    }
    try {
      for (ReportGenerator reportGenerator : getReportGenerators(controlProperties)) {
        reportGenerator.generate(results);
      }
    } catch (IOException e) {
      LOGGER.error("Could not initialize report generator.", e);
    }
  }

  private static List<IssueProcessor> getIssueProcessors(ControlProperties controlProperties, IssueClient issueClient,
                                                         RuleEngine ruleEngine) throws IOException {
    List<String> processorNames = controlProperties.getProcessors();
    List<IssueProcessor> issueProcessors = new ArrayList<>();
    if (processorNames.contains(ProcessorConstants.SIMILARITY)) {
      SimilarityProcessorProperties similarityProcessorProperties = new SimilarityProcessorProperties();
      issueProcessors.add(new SimilarityProcessor(issueClient, similarityProcessorProperties));
    }
    if (processorNames.contains(ProcessorConstants.DOCUMENTATION)) {
      DocumentationProcessorProperties documentationProcessorProperties = new DocumentationProcessorProperties();
      issueProcessors.add(new DocumentationProcessor(documentationProcessorProperties));
    }
    if (processorNames.contains(ProcessorConstants.RULE_ENGINE)) {
      issueProcessors.add(new RuleEngineProcessor(ruleEngine));
    }
    if (processorNames.contains(ProcessorConstants.LINK)) {
      issueProcessors.add(new LinkProcessor(new LinkProcessorProperties()));
    }
    if (issueProcessors.isEmpty()) {
      LOGGER.warn("Could not find any appropriate report generator in the list: {}", processorNames);
    }
    return issueProcessors;
  }

  private static List<ReportGenerator> getReportGenerators(ControlProperties controlProperties) throws IOException {
    List<String> reporterNames = controlProperties.getReporters();
    List<ReportGenerator> reportGenerators = new ArrayList<>();
    if (reporterNames.contains(ReporterConstants.HTML)) {
      reportGenerators.add(new HtmlReportGenerator(new HtmlReporterConfiguration()));
    }
    if (reporterNames.contains(ReporterConstants.TEXT)) {
      reportGenerators.add(new TextReportGenerator());
    }
    if (reportGenerators.isEmpty()) {
      LOGGER.warn("Could not find any appropriate report generator in the list: {}", reporterNames);
    }
    return reportGenerators;
  }

  public static void printFields() {
    ControlProperties controlProperties = new ControlProperties();
    try (IssueClient issueClient = getIssueClient(controlProperties)) {
      List<IssueField> fields = issueClient.getIssueFields();
      if (fields.isEmpty()) {
        LOGGER.info("Not exists available fields.");
      } else {
        LOGGER.info("Available fields:");
        fields.forEach(field -> LOGGER.info("Field '{}' with id {}", field.getName(), field.getId()));
      }
    } catch (IOException e) {
      LOGGER.error("Could not run issues processing.", e);
    }
  }

  public static void testSimilarityProcessor() {
    try {
      new TestSimilarityProcessor().test();
    } catch (IOException e) {
      LOGGER.error("Could not perform similarity processor testing...", e);
    }
  }

  private static Connector getConnector(ControlProperties controlProperties, IssueClient issueClient) {
    String connectorName = controlProperties.getConnector();
    if (ConnectorConstants.JIRA.equalsIgnoreCase(connectorName)) {
      JiraConnectorProperties jiraConnectorProperties = new JiraConnectorProperties();
      return new JiraConnector(issueClient, jiraConnectorProperties);
    } else if (ConnectorConstants.BUGZILLA.equalsIgnoreCase(connectorName)) {
      BugZillaConnectorProperties bugZillaConnectorProperties = new BugZillaConnectorProperties();
      return new BugZillaConnector(issueClient, bugZillaConnectorProperties);
    } else {
      throw new ResolverException("Could not get connector with name: " + connectorName);
    }
  }

  private static IssueClient getIssueClient(ControlProperties controlProperties) {
    String connectorName = controlProperties.getConnector();
    if (ConnectorConstants.JIRA.equalsIgnoreCase(connectorName)) {
      JiraConnectorProperties jiraConnectorProperties = new JiraConnectorProperties();
      return getJiraClientInstance(jiraConnectorProperties);
    } else if (ConnectorConstants.BUGZILLA.equalsIgnoreCase(connectorName)) {
      BugZillaConnectorProperties bugZillaConnectorProperties = new BugZillaConnectorProperties();
      return getBugZillaClientInstance(bugZillaConnectorProperties);
    } else {
      throw new ResolverException("Could not get issue client for connector with name: " + connectorName);
    }
  }

  private static IssueClient getJiraClientInstance(JiraConnectorProperties connectorProperties) {
    JiraIssueClientFactory jiraIssueClientFactory = new JiraIssueClientFactory(connectorProperties);
    IssueClient jiraClient;
    try {
      if (connectorProperties.isAnonymous()) {
        jiraClient = jiraIssueClientFactory.getAnonymousClient(connectorProperties.getUrl());
      } else {
        Credentials credentials = getCredentials(connectorProperties.getCredentialsFolder());
        jiraClient = jiraIssueClientFactory.getBasicClient(connectorProperties.getUrl(), credentials);
      }
    } catch (JiraConnectorException e) {
      throw new ResolverException("Could not initialize Jira client.", e);
    }
    return jiraClient;
  }

  private static IssueClient getBugZillaClientInstance(BugZillaConnectorProperties connectorProperties) {
    BugZillaIssueClientFactory bugZillaIssueClientFactory = new BugZillaIssueClientFactory();
    IssueClient bugZillaClient;
    try {
      if (connectorProperties.isAnonymous()) {
        bugZillaClient = bugZillaIssueClientFactory.getAnonymousClient(connectorProperties.getUrl());
      } else {
        Credentials credentials = getCredentials(connectorProperties.getCredentialsFolder());
        bugZillaClient = bugZillaIssueClientFactory.getBasicClient(connectorProperties.getUrl(), credentials);
      }
    } catch (BugZillaConnectorException e) {
      throw new ResolverException("Could not initialize BugZilla client.", e);
    }
    return bugZillaClient;
  }

  private static Credentials getCredentials(String credentialsFolder) {
    CredentialsProtector protector = new CredentialsProtector();
    CredentialsKeeper keeper = new CredentialsKeeper(protector, credentialsFolder);
    Credentials credentials;
    if (keeper.isStored()) {
      credentials = keeper.load();
    } else {
      String username = CommandLineUtil.getUsername();
      String password = CommandLineUtil.getPassword();
      credentials = new Credentials(username, password);
      keeper.store(credentials);
    }
    return credentials;
  }
}
