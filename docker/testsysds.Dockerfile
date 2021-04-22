#-------------------------------------------------------------
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
#-------------------------------------------------------------

# Use R official debian release 
FROM r-base

WORKDIR /usr/src/

# Install Maven
# Credit https://github.com/Zenika/alpine-maven/blob/master/jdk8/Dockerfile

ENV MAVEN_VERSION 3.6.3
ENV MAVEN_HOME /usr/lib/mvn
ENV JAVA_HOME /usr/lib/jvm/java-8-openjdk-amd64
ENV PATH $JAVA_HOME/bin:$MAVEN_HOME/bin:$PATH

RUN mkdir /usr/lib/jvm
RUN wget -qO- \
https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u282-b08/OpenJDK8U-jdk_x64_linux_hotspot_8u282b08.tar.gz \
| tar xzf -
RUN mv jdk8u282-b08 /usr/lib/jvm/java-8-openjdk-amd64

RUN wget -qO- \
http://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz | tar xzf -
RUN mv apache-maven-$MAVEN_VERSION /usr/lib/mvn

# Install Extras
RUN apt-get update -qq && \
	apt-get upgrade -y && \
	apt-get install libcurl4-openssl-dev -y && \
	apt-get install libxml2-dev -y && \
	apt-get install r-cran-xml -y 

COPY ./src/test/scripts/installDependencies.R installDependencies.R

# Install R + Dependencies
RUN Rscript installDependencies.R

COPY ./docker/entrypoint.sh /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]