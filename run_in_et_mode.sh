#!/bin/bash

sbt "~run -Drun.mode=Stub  -Dhttp.port=9680 -DisExternalTestEnvironment=true $*"