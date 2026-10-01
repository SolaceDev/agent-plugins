# Prerequisites

Read this when a developer does not yet have a broker to build against, or when the developer's Python environment is in question. Both Design mode and Implement mode route here first if the developer lacks a reachable broker.

## Table of contents

- Obtain a broker
- Supported environments
- Learn the Python API basics

## Obtain a broker

You need one reachable broker. The primary, recommended choice is [Solace Cloud](https://docs.solace.com/Get-Started/Getting-Started-Try-Broker.md): it is the simplest path to a running broker for a greenfield app and for the run-and-observe round-trip. Default to it unless the developer has a reason not to.

As brief alternatives, the same page also documents a self-hosted Software Broker (run locally via container or VM) and an Appliance (existing hardware the developer already operates) for developers who cannot use Solace Cloud.

The skill does not provision or configure the broker. It assumes the broker is reachable and that the developer has connection details (host, message VPN, client username, password). A broker discovered running in the environment (for example a local container) is a fact to report, never an answer: still ask which broker the developer wants to target.

## Supported environments

WebFetch [Supported Environments for the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-supported-Environments.md) when the developer's environment is in question, and state the answer from the fetched page. Three points matter for every generated project:

- **Python version.** Solace supports the Python versions that are in active support and also have security update support; the page links the python.org list. The `requires_python` floor on PyPI is an install floor, not a support statement, so never recommend a Python version that is past its end of life because the package still installs on it.
- **OpenSSL.** TLS connections need OpenSSL. The page says which platforms and package versions bundle it and that the bundled libraries are for developer convenience only, so a Linux deployment installs its own.
- **No `multiprocessing`.** The Solace Python API cannot be used in an application that uses the Python `multiprocessing` module. Only the [solace-pubsubplus PyPI page](https://pypi.org/project/solace-pubsubplus/) states this; cite that page, not a `docs.solace.com` page.

## Learn the Python API basics

For a tutorial-style, code-first introduction to connecting, publishing, and receiving with the Python API, point the developer at the official Python tutorials: https://tutorials.solace.dev/python/. These walk the same connect, publish, and subscribe flow the skill builds on.
