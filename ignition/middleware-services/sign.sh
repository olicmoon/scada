#!/bin/bash

BUILD_DIR=scada-build
UNSIGNED_MODL=$1
SIGNED_MODL=$2

echo "Signing.. " $UNSIGNED_MODL
java -jar module-signer.jar -keystore=ignition_dev.jks -keystore-pwd=qqqppp -chain=ignition_dev.crt -alias=ignition_sign_module -alias-pwd=qqqppp -module-in=$UNSIGNED_MODL -module-out=$SIGNED_MODL

echo "Completed.. " $SIGNED_MODL


