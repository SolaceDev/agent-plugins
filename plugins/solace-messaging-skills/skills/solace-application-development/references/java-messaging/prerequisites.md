# Prerequisites

Read this when a developer does not yet have a broker to build against, or when the developer's Java environment is in question. Both Design mode and Implement mode route here first if the developer lacks a reachable broker.

## Table of contents

- Obtain a broker
- Supported environments
- Learn the Java API basics

## Obtain a broker

You need one reachable broker. The primary, recommended choice is [Solace Cloud](https://docs.solace.com/Get-Started/Getting-Started-Try-Broker.md): it is the simplest path to a running broker for a greenfield app and for the run-and-observe round-trip. Default to it unless the developer has a reason not to.

As a brief alternative, the same page also covers setting up a free broker locally, for developers who cannot use Solace Cloud.

The skill does not provision or configure the broker. It assumes the broker is reachable and that the developer has connection details (host, message VPN, client username, password). A broker discovered running in the environment (for example a local container) is a fact to report, never an answer: still ask which broker the developer wants to target.

## Supported environments

WebFetch [Supported Environments for the Solace Java API](https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-supported-environments.md) when the developer's environment is in question, and state the answer from the fetched page. Two points matter for every generated project:

- **JDK version.** Solace supports the Oracle JDK and OpenJDK versions that are in active support and also have security update support; the page links the Oracle support roadmap. Check `java -version` in the developer's environment. When it reports a JDK that is no longer in support, tell the developer and link the page; do not choose an older JDK for them. The dependency snippet on that page carries a version number; never copy it (SKILL.md Invariant 3).
- **Maven.** The generated project is a Maven project, so `mvn -v` must work and must report the JDK the developer means to use. The page also shows a Gradle form of the dependency; this skill generates Maven only.

## Learn the Java API basics

For a tutorial-style, code-first introduction to connecting, publishing, and receiving with the Java API, point the developer at the official Java API tutorials: https://tutorials.solace.dev/java/. These walk the same connect, publish, and subscribe flow the skill builds on.
