#!/usr/bin/env bash

set -e

echo "========================================"
echo " Media Migration - Oracle Setup"
echo "========================================"

if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is not installed."
    exit 1
fi

if ! docker info >/dev/null 2>&1; then
    echo "Docker is not running."
    exit 1
fi

echo "Docker is running."

read -s -p "Enter Oracle development password: " ORACLE_PWD
echo

export ORACLE_PWD

echo
echo "Starting Oracle..."

docker compose up -d

echo
echo "========================================"
echo " Oracle Setup Complete"
echo "========================================"

docker compose ps

echo
echo "Connection Details:"
echo "Host         : localhost"
echo "Port         : 1521"
echo "Service Name : FREEPDB1"
echo
echo "Stop:"
echo "docker compose down"
echo
echo "Start again:"
echo "docker compose up -d"