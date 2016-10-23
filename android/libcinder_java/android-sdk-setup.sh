#!/bin/bash

# Author: Tobias Preuss
# Version: 2016-04-08

echo "Creating symbolic links in Android SDK folder"
echo "============================================="
echo

if [ -z "$ANDROID_HOME" ] ; then
  echo "Could not find Android SDK. Environment variable ANDROID_HOME is not defined."
  exit 1
fi

if [ ! -d "$ANDROID_HOME/build-tools/24.0.3" ] ; then
  echo "Could not find build-tools folder 24.0.3. Please download the latest version first."
  exit 1
fi

echo "Found ANDROID_HOME set to $ANDROID_HOME"
echo

# For IntelliJ and Android-Studio
ln -s $ANDROID_HOME/platform-tools/adb $ANDROID_HOME/tools/adb
ln -s $ANDROID_HOME/build-tools/24.0.3/aapt $ANDROID_HOME/tools/aapt
ln -s $ANDROID_HOME/build-tools/24.0.3/dx $ANDROID_HOME/tools/dx
ln -s $ANDROID_HOME/build-tools/24.0.3/zipalign $ANDROID_HOME/tools/zipalign
ln -s $ANDROID_HOME/build-tools/24.0.3/lib/dx.jar $ANDROID_HOME/tools/dx.jar

# For Gradle
ln -s $ANDROID_HOME/build-tools/24.0.3/lib/dx.jar $ANDROID_HOME/build-tools/24.0.3/lib/dx.jar

