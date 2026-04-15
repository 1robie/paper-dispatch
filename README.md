# [![](https://jitpack.io/v/1robie/paper-dispatch.svg)](https://jitpack.io/#1robie/paper-dispatch)
![MIT License](https://img.shields.io/badge/license-MIT-green.svg)
![Java 21](https://img.shields.io/badge/java-21-blue.svg)
<!-- ![Build Status](https://github.com/1robie/Paper-Dispatch/actions/workflows/build.yml/badge.svg) -->

# 📦 Paper-Dispatch

A multi-module Maven library for PaperMC plugins, providing a shared API and implementation layer to streamline plugin
development.

---

## 🧩 Modules

| Module           | Description                            |
|------------------|----------------------------------------|
| `Lib-API`        | Public API interfaces and abstractions |
| `paper-dispatch` | Full implementation (includes Lib-API) |
| `Exemple-Plugin` | Example plugin using the library       |

---

## 📥 Installation (via JitPack)

### 1. Add the JitPack repository

```xml

<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

### 2. Add the dependency

```xml

<dependency>
    <groupId>com.github.1robie.Paper-Dispatch</groupId>
    <artifactId>paper-dispatch</artifactId>
    <version>v0.0.1</version>
</dependency>
```

> Replace `v0.0.1` with the [latest release tag](https://github.com/1robie/Paper-Dispatch/releases).

---

## ⚙️ Requirements

- Java **21**
- PaperMC **1.21.x**
- Maven **3.6+**

---

## 🏗️ Building from source

```bash
git clone https://github.com/1robie/Paper-Dispatch.git
cd Paper-Dispatch
mvn clean package
```

The output JARs will be in each module's `target/` directory.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE)