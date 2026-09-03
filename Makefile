SHELL := /bin/bash
JAVA_HOME ?= /opt/homebrew/opt/openjdk@17
export PATH := $(JAVA_HOME)/bin:$(PATH)

.PHONY: all test dashboard sidecar mod package clean

all: test

test:
	cd dashboard && pnpm lint && pnpm test -- --run && pnpm build
	cd sidecar && go fmt ./... && go vet ./... && go test ./...
	cd mod && ./gradlew test build

dashboard:
	cd dashboard && pnpm build

sidecar:
	cd sidecar && mkdir -p build && go build -o build/ae2-dashboard .

mod:
	cd mod && ./gradlew build

package:
	./scripts/package.sh

clean:
	rm -rf artifacts dashboard/dist sidecar/build mod/build
