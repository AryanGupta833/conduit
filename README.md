# Conduit

> A distributed workflow orchestration platform built with Spring Boot for executing Directed Acyclic Graph (DAG) based workflows with fault tolerance, scheduling, and extensibility.

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-brightgreen)
![MySQL](https://img.shields.io/badge/MySQL-Database-blue)
![License](https://img.shields.io/badge/License-MIT-green)

---

## Overview

Conduit is a backend workflow orchestration platform inspired by systems like Apache Airflow, Netflix Conductor, and Temporal. It enables users to define workflows as Directed Acyclic Graphs (DAGs), where each task executes only after all its dependencies have completed successfully.

The platform focuses on reliable workflow execution by providing dependency-aware scheduling, concurrent execution, retry mechanisms, timeout handling, execution persistence, and comprehensive monitoring.

Instead of executing tasks sequentially, Conduit analyzes workflow dependencies using graph algorithms and executes independent tasks in parallel, significantly improving execution efficiency.

---

## Key Features

### DAG-Based Workflow Execution

- Directed Acyclic Graph validation
- Cycle detection
- Topological sorting
- Dependency-aware execution
- Parallel execution of independent tasks

---

### Fault-Tolerant Execution Engine

- Configurable retry policies
- Task timeout handling
- Persistent execution state
- Automatic failure recovery
- Detailed execution logs

---

### Workflow Management

- Workflow versioning
- Workflow scheduling
- Execution planning
- Graph visualization
- Monitoring dashboard APIs

---

### Concurrent Execution

Conduit executes independent workflow nodes simultaneously using Java's ExecutorService while ensuring dependency constraints are always respected.

Example:

```
          Extract Data
          /          \
   Validate      Transform
          \          /
          Load Database
                |
          Send Notification
```

Both **Validate** and **Transform** execute concurrently once **Extract Data** finishes.

---

### Plugin Architecture

Conduit is designed to be extensible through a plugin-driven execution model.

Future plugins can support:

- HTTP Tasks
- Database Tasks
- Email Tasks
- Shell Commands
- Kafka
- RabbitMQ
- Docker Tasks

without modifying the execution engine.

---

## System Architecture

```
              REST API
                  │
                  ▼
        Workflow Controller
                  │
                  ▼
         Workflow Service
                  │
                  ▼
        DAG Validation Engine
                  │
                  ▼
      Topological Sort Planner
                  │
                  ▼
      Concurrent Execution Engine
                  │
          ┌───────┴────────┐
          ▼                ▼
     Task Executor    Retry Manager
          │                │
          └───────┬────────┘
                  ▼
        Execution Persistence
                  │
                  ▼
              MySQL Database
```

---

## Technology Stack

- Java
- Spring Boot
- Spring Data JPA
- MySQL
- ExecutorService
- Resilience4j
- OpenAPI / Swagger
- Maven

---

## Core Components

### Workflow Engine

Responsible for

- parsing workflows
- validating DAGs
- dependency analysis
- execution planning

---

### Execution Service

Handles

- workflow execution
- task scheduling
- retries
- timeout management
- execution persistence

---

### Scheduler

Supports scheduled workflow execution using configurable cron expressions.

Example:

```
0 */5 * * * *
```

Runs every 5 minutes.

---

### Task Runner

Responsible for

- task execution
- retry logic
- timeout enforcement
- execution status updates

---

## Database Model

Main entities:

- Workflow
- WorkflowVersion
- TaskNode
- WorkflowExecution
- TaskExecution

---

## Execution Flow

```
Create Workflow
        │
        ▼
Validate DAG
        │
        ▼
Topological Sort
        │
        ▼
Generate Execution Plan
        │
        ▼
Execute Independent Tasks
        │
        ▼
Retry Failed Tasks
        │
        ▼
Persist Execution State
        │
        ▼
Workflow Completed
```

---

## REST APIs

### Workflow APIs

```
POST   /api/workflows
GET    /api/workflows
GET    /api/workflows/{id}
DELETE /api/workflows/{id}
```

---

### Execution APIs

```
POST /api/workflows/{id}/execute
GET  /api/executions/{id}
GET  /api/workflows/{id}/plan
```

---

### Monitoring APIs

```
GET /api/executions
GET /api/executions/{id}/logs
```

---

## Performance Highlights

- DAG-based dependency resolution
- Parallel execution of independent tasks
- Configurable retry policies
- Persistent execution tracking
- Fault-tolerant workflow execution
- Support for 100+ task workflows

---

## Roadmap

### Completed

- DAG validation
- Topological sorting
- Concurrent execution
- Retry mechanism
- Timeout support
- Workflow scheduling
- Execution persistence
- Monitoring APIs

---

### Planned

- Docker task execution
- Kubernetes integration
- Distributed worker nodes
- Kafka task plugin
- RabbitMQ task plugin
- Expression engine
- Workflow variables
- Plugin SDK
- Web UI
- Metrics dashboard

---

## Running the Project

```bash
git clone https://github.com/AryanGupta833/conduit.git

cd conduit

mvn spring-boot:run
```

---

## Future Vision

Conduit aims to become a lightweight workflow orchestration platform for backend automation, microservice coordination, and cloud-native task execution while remaining modular and highly extensible.

---

## Author

**Aryan Gupta**

- GitHub: https://github.com/AryanGupta833
- LinkedIn: https://linkedin.com/in/aryan-gupta-316a35366
