# Prerequisites

Read this when a developer is new to Solace or does not yet have a broker to build against. Both Design mode and Implement mode route here first if the developer lacks basic Solace grounding or a reachable broker.

## Table of contents

- What is Solace
- Obtain a broker
- Learn the JCSMP basics

## What is Solace

Ground the developer in the high-level model (event broker, event mesh, topics, queues) before discussing patterns or generating code: [Solace Core Concepts](https://docs.solace.com/Get-Started/event-mesh-basics.md). Do not paraphrase the page. Point the developer at it, confirm they grasp the publish/subscribe and queue concepts, then continue.

## Obtain a broker

You need one reachable broker. The primary, recommended choice is [Solace Cloud](https://docs.solace.com/Get-Started/Getting-Started-Try-Broker.md): it is the simplest path to a running broker for a greenfield app and for the run-and-observe round-trip. Default to it unless the developer has a reason not to.

As brief alternatives, the same page also documents a self-hosted Software Broker (run locally via container or VM) and an Appliance (existing hardware the developer already operates) for developers who cannot use Solace Cloud.

The skill does not provision or configure the broker. It assumes the broker is reachable and that the developer has connection details (host, message VPN, client username, password).

## Learn the JCSMP basics

For a tutorial-style, code-first introduction to connecting, publishing, and consuming with JCSMP, point the developer at the official JCSMP tutorials: https://tutorials.solace.dev/jcsmp/. These walk the same connect/publish/subscribe flow the skill builds on.
