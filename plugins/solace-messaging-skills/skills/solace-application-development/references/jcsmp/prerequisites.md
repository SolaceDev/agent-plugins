# Prerequisites

Read this when a developer does not yet have a broker to build against. Both Design mode and Implement mode route here first if the developer lacks a reachable broker.

## Table of contents

- Obtain a broker
- Learn the JCSMP basics

## Obtain a broker

You need one reachable broker. The primary, recommended choice is [Solace Cloud](https://docs.solace.com/Get-Started/Getting-Started-Try-Broker.md): it is the simplest path to a running broker for a greenfield app and for the run-and-observe round-trip. Default to it unless the developer has a reason not to.

As brief alternatives, the same page also documents a self-hosted Software Broker (run locally via container or VM) and an Appliance (existing hardware the developer already operates) for developers who cannot use Solace Cloud.

The skill does not provision or configure the broker. It assumes the broker is reachable and that the developer has connection details (host, message VPN, client username, password). A broker discovered running in the environment (for example a local container) is a fact to report, never an answer: still ask which broker the developer wants to target.

## Learn the JCSMP basics

For a tutorial-style, code-first introduction to connecting, publishing, and consuming with JCSMP, point the developer at the official JCSMP tutorials: https://tutorials.solace.dev/jcsmp/. These walk the same connect/publish/subscribe flow the skill builds on.
