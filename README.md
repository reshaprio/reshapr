[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/reshaprio/reshapr/build-verify.yml?logo=github&style=for-the-badge)](https://github.com/reshaprio/reshapr/actions)
[![Container](https://img.shields.io/badge/dynamic/json?color=blueviolet&logo=docker&style=for-the-badge&label=Quay.io&query=tags[1].name&url=https://quay.io/api/v1/repository/reshapr/reshapr-ctrl/tag/?limit=10&page=1&onlyActiveTags=true)](https://quay.io/repository/reshapr/reshapr-ctrl?tab=tags)
[![Version](https://img.shields.io/maven-central/v/io.reshapr/reshapr-parent?color=blue&logo=openjdk&style=for-the-badge)]((https://central.sonatype.com/search?q=io.reshapr))
[![NPM](https://img.shields.io/npm/v/@reshapr/reshapr-cli?color=CB3837&logo=npm&style=for-the-badge)](https://www.npmjs.com/package/@reshapr/reshapr-cli)
[![NPM Downloads](https://img.shields.io/npm/dm/@reshapr/reshapr-cli?color=CB3837&logo=npm&style=for-the-badge&label=CLI%20downloads)](https://www.npmjs.com/package/@reshapr/reshapr-cli)
[![License](https://img.shields.io/github/license/reshaprio/reshapr?style=for-the-badge&logo=apache)](https://www.apache.org/licenses/LICENSE-2.0)
[![Project Chat](https://img.shields.io/badge/discord-reshapr-pink.svg?color=7289da&style=for-the-badge&logo=discord)](https://discord.gg/KyDUdam34h)
[![GitHub stars](https://img.shields.io/github/stars/reshaprio/reshapr?style=for-the-badge&logo=github&color=ffad05)](https://github.com/reshaprio/reshapr)
[![GitHub forks](https://img.shields.io/github/forks/reshaprio/reshapr?style=for-the-badge&logo=github&color=ffad05)](https://github.com/reshaprio/reshapr/forks)

# Welcome to reShapr 🚀

[**Website**](https://reshapr.io/) | [**About**](https://reshapr.io/about) | [**Docs**](https://reshapr.io/docs/) | [**Blog**](https://reshapr.io/blog) | [**Community**](https://reshapr.io/community)

---

## ✨ What is reShapr?

**reShapr** is the open source, no-code MCP Server for AI-Native API Access. It bridges the gap between traditional REST/GraphQL/gRPC services and LLMs by transforming complex services into discoverable, optimized MCP tools.

<p align="center">
  <a href="https://reshapr.io/docs/tutorials/getting-started">
    <img src="https://raw.githubusercontent.com/reshaprio/.github/main/assets/demo-video-github-full.gif" width="750" alt="reShapr Demo - Click to get started">
  </a>
  <br/>
  <em>✨ Transform any API into lean LLM contexts in seconds.</em>
</p>

> [!IMPORTANT]
> To get involved with our community, please familiarize yourself with the project's [Code of Conduct](./CODE_OF_CONDUCT.md).

### 🧠 Solving the "Context Window" Problem

One of the biggest hurdles in building AI agents is **Context Overload**. Sending massive JSON responses to an LLM wastes tokens and degrades performance. 

[**reShapr introduces Context Control**](https://reshapr.io/blog/from-context-overload-to-context-control)

## 🌟 Key Features

* AI-Native Transformation: Auto-transform API services into LLM-friendly tools.
* Context Control: Filter and slim down payloads before they reach the LLM.
* Multi-Protocol: REST, GraphQL, and gRPC support.
* No-Code: Configure and deploy in minutes.

📖 [**Full Documentation**](https://reshapr.io/docs/) | [**Discord Community**](https://discord.gg/KyDUdam34h)

## Quick Start

### Install the CLI

*(Requires [Node.js](https://nodejs.org/) installed)*
```sh
npm install -g @reshapr/reshapr-cli
```

### Choose your platform

Choose how to get started:

1. **Try it online:** Use [try.reshapr.io](https://try.reshapr.io/) for a fully managed sandbox. Follow our [Try reShapr online](https://reshapr.io/docs/tutorials/try-reshapr-online) guide.
2. **Run it locally (End-User):** See the section below to spin up the latest published release.
3. **Contribute code / launch the Web UI (Developer):** Head to [install/README.md](install/README.md) to run the `docker-compose` stack — it uses `:nightly` development builds.

### Run the platform locally

Assuming you have Docker installed, you can run the platform locally with the following command:
```sh
reshapr run
```

You should see a message like this:
```sh
ℹ️  Resolved 'latest' to release '1.0.0'.
ℹ️  Downloading compose file from https://raw.githubusercontent.com/reshaprio/reshapr/refs/tags/1.0.0/install/docker-compose-all-in-one.yml...
✅ Compose file saved to /Users/<you>/.reshapr/docker-compose-1.0.0.yml
ℹ️  Starting Reshapr containers (release: 1.0.0)...
✅ Reshapr containers started successfully.
```

Connect to the control plane at http://localhost:5555 with default credentials (`admin/password`):
```sh
reshapr login -s http://localhost:5555 -u admin -p password
```

Import an OpenAPI file:
```sh
reshapr import -u https://raw.githubusercontent.com/open-meteo/open-meteo/refs/heads/main/openapi/forecast.yml \
  --backendEndpoint https://api.open-meteo.com
```

You now have a fully functional MCP Server powered by reShapr at http://localhost:7777/mcp/reshapr/open-meteo-weather-forecast-api-1-0-default 🎉

You can try it out using an MCP client, [explore the CLI](https://reshapr.io/docs/tutorials/getting-started) and 
[more features](https://reshapr.io/docs/references/features)!

## Build Status

The current development version is `1.0.1-SNAPSHOT`.

[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/reshaprio/reshapr/build-verify.yml?logo=github&style=for-the-badge)](https://github.com/reshaprio/reshapr/actions)

#### Signature, Provenance, SBOM

[![Static Badge](https://img.shields.io/badge/supply_chain-signed_SBOM_%26_checksums-blue?logo=securityscorecard&label=Supply%20Chain)](https://github.com/reshaprio/reshapr/releases)

#### Sonarcloud Quality metrics
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=reshaprio_reshapr&metric=code_smells)](https://sonarcloud.io/summary/overall?id=reshaprio_reshapr&branch=main)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=reshaprio_reshapr&metric=reliability_rating)](https://sonarcloud.io/summary/overall?id=reshaprio_reshapr&branch=main)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=reshaprio_reshapr&metric=bugs)](https://sonarcloud.io/summary/overall?id=reshaprio_reshapr&branch=main)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=reshaprio_reshapr&metric=coverage)](https://sonarcloud.io/summary/overall?id=reshaprio_reshapr&branch=main)
[![Technical Debt](https://sonarcloud.io/api/project_badges/measure?project=reshaprio_reshapr&metric=sqale_index)](https://sonarcloud.io/summary/overall?id=reshaprio_reshapr&branch=main)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=reshaprio_reshapr&metric=security_rating)](https://sonarcloud.io/summary/overall?id=reshaprio_reshapr&branch=main)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=reshaprio_reshapr&metric=sqale_rating)](https://sonarcloud.io/summary/overall?id=reshaprio_reshapr&branch=main)

## Contributing

We ❤️ contributors! Reshapr is an open community, and we welcome contributions of **all kinds** — not just code. 
Every contribution matters and helps make Reshapr better for everyone:

- 🐛 **Report bugs** or submit support requests via [GitHub Issues](https://github.com/reshaprio/reshapr/issues)
- 📖 **Improve documentation** — fix a typo, clarify a guide, or write a new tutorial
- 🧪 **Write tests** to increase coverage and reliability
- 🎥 **Create demos** or share your use cases with the community
- 💻 **Submit code** — bug fixes, features, or refactors are all welcome
- 💬 **Share testimonials** — tell us how you use Reshapr and help spread the word

> 📋 Before contributing, please read our [Contributing Guide](./CONTRIBUTING.md) to learn about our development workflow, 
coding conventions, and how to submit your changes.

Come say hi on our [Discord server](https://discord.gg/KyDUdam34h) — whether you have questions, ideas, or just want to 
chat with the community. We'd love to hear from you! 🚀

## Support the project! 🌟

If you think reShapr is useful for the AI ecosystem, [**give us a star** on our main repository](https://github.com/reshaprio/reshapr)! It helps us get more visibility and keep the project growing.

### Thanks!

[![Contributors](https://contrib.rocks/image?repo=reshaprio/reshapr)](https://github.com/reshaprio/reshapr/graphs/contributors)
[![Forkers repo roster for @reshaprio/reshapr](http://reporoster.com/forks/reshaprio/reshapr)](http://github.com/reshaprio/reshapr/network/members)
