#!/usr/bin/env bash
# Shortel — Build all services and start with Docker Compose
set -e

echo "=== Shortel Platform Build & Run ==="
echo ""

# Check prerequisites
command -v docker >/dev/null 2>&1 || { echo "Docker not found. Please install Docker Desktop."; exit 1; }
command -v docker-compose >/dev/null 2>&1 || docker compose version >/dev/null 2>&1 || { echo "docker-compose not found."; exit 1; }

echo "1. Pulling base images..."
docker pull mysql:8.0 -q
docker pull redis:7.2-alpine -q
docker pull confluentinc/cp-zookeeper:7.6.0 -q
docker pull confluentinc/cp-kafka:7.6.0 -q
docker pull maven:3.9.6-eclipse-temurin-21 -q
docker pull eclipse-temurin:21-jre-alpine -q

echo "2. Building all service images..."
docker compose build --parallel

echo "3. Starting infrastructure (MySQL, Redis, Kafka)..."
docker compose up -d mysql redis zookeeper kafka

echo "4. Waiting for infrastructure to be healthy..."
sleep 15

echo "5. Starting microservices..."
docker compose up -d id-generator-service auth-service tenant-service

echo "6. Waiting for core services..."
sleep 30

echo "7. Starting remaining services..."
docker compose up -d url-service redirect-service analytics-service

echo "8. Waiting for dependent services..."
sleep 30

echo "9. Starting API Gateway..."
docker compose up -d api-gateway

echo ""
echo "=== All services started! ==="
echo ""
echo "Service endpoints:"
echo "  API Gateway:      http://localhost:8080"
echo "  Auth Service:     http://localhost:8081"
echo "  URL Service:      http://localhost:8082"
echo "  Redirect Service: http://localhost:8083"
echo "  Analytics:        http://localhost:8084"
echo "  Tenant Service:   http://localhost:8085"
echo "  ID Generator:     http://localhost:8086"
echo ""
echo "Health checks:"
for port in 8080 8081 8082 8083 8084 8085 8086; do
  status=$(curl -sf "http://localhost:$port/actuator/health" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "starting...")
  echo "  :$port → $status"
done
echo ""
echo "View logs: docker compose logs -f [service-name]"
echo "Stop all:  docker compose down"
