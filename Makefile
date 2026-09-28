SHELL := /bin/bash

-include .env
PHONE_HOST ?= pixel-5
SSH_PORT   ?= 8022
APK        := app/build/outputs/apk/debug/app-debug.apk

SHERPA_VERSION := 1.13.8
SHERPA_SHA256  := 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96
SHERPA_AAR     := app/libs/sherpa-onnx-$(SHERPA_VERSION).aar

WHISPER_VERSION := 1.9.4
WHISPER_SHA256  := 57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae
WHISPER_SRC     := app/whisper.cpp

# Gradle uses JAVA_HOME if set, otherwise `java` on PATH (JDK 17-27)
ifeq ($(shell uname),Darwin)
ANDROID_HOME ?= $(HOME)/Library/Android/sdk
else
ANDROID_HOME ?= $(HOME)/Android/Sdk
endif
export ANDROID_HOME

.PHONY: deps build test install adb-install push-model clean

## Fetch the official sherpa-onnx AAR and whisper.cpp source, verifying their SHA-256
deps: $(SHERPA_AAR) $(WHISPER_SRC)/CMakeLists.txt

$(SHERPA_AAR):
	mkdir -p $(dir $@)
	curl -fL -o $@.tmp https://github.com/k2-fsa/sherpa-onnx/releases/download/v$(SHERPA_VERSION)/sherpa-onnx-$(SHERPA_VERSION).aar
	echo "$(SHERPA_SHA256)  $@.tmp" | shasum -a 256 -c
	mv $@.tmp $@

$(WHISPER_SRC)/CMakeLists.txt:
	curl -fL -o $(WHISPER_SRC).tar.gz https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/refs/tags/v$(WHISPER_VERSION)
	echo "$(WHISPER_SHA256)  $(WHISPER_SRC).tar.gz" | shasum -a 256 -c
	rm -rf $(WHISPER_SRC) && mkdir -p $(WHISPER_SRC)
	tar xzf $(WHISPER_SRC).tar.gz -C $(WHISPER_SRC) --strip-components=1
	rm $(WHISPER_SRC).tar.gz

build: deps
	./gradlew assembleDebug
	@echo "APK: $(APK)"

test: deps
	./gradlew testDebugUnitTest

install: build
	scp -P $(SSH_PORT) $(APK) $(PHONE_HOST):~/storage/downloads/whisalt.apk
	ssh -p $(SSH_PORT) $(PHONE_HOST) "termux-open ~/storage/downloads/whisalt.apk"
	@echo "APK sent — approve install on phone"

adb-install: build
	$(ANDROID_HOME)/platform-tools/adb install -r $(APK)

## Push a model to the phone's internal storage (usage: make push-model MODEL=/path/to/model-dir)
push-model:
	@test -n "$(MODEL)" || (echo "Usage: make push-model MODEL=/path/to/model-dir" && exit 1)
	$(ANDROID_HOME)/platform-tools/adb push $(MODEL)/ /data/local/tmp/$(notdir $(MODEL))/
	$(ANDROID_HOME)/platform-tools/adb shell "run-as com.saltchang.whisalt mkdir -p files/models/$(notdir $(MODEL))"
	@for f in $$(ls $(MODEL)/*.onnx $(MODEL)/*.ort $(MODEL)/*.txt 2>/dev/null); do \
		echo "  copying $$(basename $$f)..."; \
		$(ANDROID_HOME)/platform-tools/adb shell "run-as com.saltchang.whisalt cp /data/local/tmp/$(notdir $(MODEL))/$$(basename $$f) files/models/$(notdir $(MODEL))/$$(basename $$f)"; \
	done
	@echo "Model pushed: $(notdir $(MODEL))"

clean:
	./gradlew clean
