import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import styles from './index.module.css';

function HomepageHeader() {
  const { siteConfig } = useDocusaurusContext();
  return (
    <header className={styles.heroBanner}>
      <div className="container">
        <Heading as="h1" className="hero__title">
          {siteConfig.title}
        </Heading>
        <p className="hero__subtitle">{siteConfig.tagline}</p>
        <div className={styles.buttons}>
          <a className="button button--primary button--lg" href="/docs/intro">
            Começar
          </a>
          <a
            className="button button--secondary button--lg"
            href="https://github.com/archflow/archflow"
            target="_blank"
            rel="noopener noreferrer">
            GitHub
          </a>
        </div>
      </div>
    </header>
  );
}

function Feature({ title, description, icon }) {
  return (
    <div className="col col--4">
      <div className="text--center padding-horiz--md">
        <span className={styles.featureIcon}>{icon}</span>
        <Heading as="h3">{title}</Heading>
        <p>{description}</p>
      </div>
    </div>
  );
}

export default function Home(): JSX.Element {
  const { siteConfig } = useDocusaurusContext();
  return (
    <Layout title={`${siteConfig.title}`} description={siteConfig.tagline}>
      <HomepageHeader />
      <main>
        <section className={styles.features}>
          <div className="container">
            <div className="row">
              <Feature
                icon="🎨"
                title="Visual Builder"
                description="Crie workflows arrastando e soltando nós. Web Component que funciona com React, Vue, Angular ou vanilla JS."
              />
              <Feature
                icon="☕"
                title="100% Java"
                description="Integração nativa com Spring Boot e ecossistema Java. Nenhum runtime Python necessário."
              />
              <Feature
                icon="🤖"
                title="LangChain4j"
                description="Framework de IA mais moderno do ecossistema Java. Suporte a 15+ provedores LLM."
              />
            </div>
            <div className="row" style={{ marginTop: '2rem' }}>
              <Feature
                icon="🔌"
                title="MCP Native"
                description="Integração com Model Context Protocol para interoperabilidade com outras ferramentas AI."
              />
              <Feature
                icon="🏢"
                title="Enterprise-Ready"
                description="RBAC, observabilidade, tracing e métricas. Pronto para produção desde dia 1."
              />
              <Feature
                icon="📦"
                title="Templates Prontos"
                description="Use templates para casos comuns como suporte ao cliente, RAG e multi-agent systems."
              />
            </div>
          </div>
        </section>

        <section className={styles.codeSection}>
          <div className="container">
            <Heading as="h2" style={{ textAlign: 'center', marginBottom: '2rem' }}>
              Comece em Minutos
            </Heading>
            <div className={styles.codeBlock}>
              <div className={styles.codeBlockHeader}>
                <span> Maven (pom.xml) </span>
              </div>
              <pre style={{ margin: 0, padding: '1rem', background: '#1e1e1e', color: '#d4d4d4', borderRadius: '0 0.5rem 0.5rem 0.5rem', overflow: 'auto' }}>
                <code>{`<dependency>
    <groupId>br.com.archflow</groupId>
    <artifactId>archflow-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>`}</code>
              </pre>
            </div>
            <div className={styles.codeBlock}>
              <div className={styles.codeBlockHeader}>
                <span> Web Component </span>
              </div>
              <pre style={{ margin: 0, padding: '1rem', background: '#1e1e1e', color: '#d4d4d4', borderRadius: '0 0.5rem 0.5rem 0.5rem', overflow: 'auto' }}>
                <code>{`<archflow-designer
  workflow-id="my-workflow"
  api-base="http://localhost:8080/api"
  theme="dark">
</archflow-designer>`}</code>
              </pre>
            </div>
          </div>
        </section>
      </main>
    </Layout>
  );
}
