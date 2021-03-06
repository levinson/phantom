#!/usr/bin/env bash
if [ "${TRAVIS_SCALA_VERSION}" == "2.11.7" ] && [ "${TRAVIS_JDK_VERSION}" == "oraclejdk8" ];
then
    echo "Running tests with aggregation"
    sbt ++$TRAVIS_SCALA_VERSION clean coverage test coverageReport coverageAggregate coveralls
else
    echo "Running tests without attempting to submit coverage reports"
    sbt ++$TRAVIS_SCALA_VERSION clean coverage test
    echo "Only publishing version for Scala 2.11.7 and Oracle JDK 8 to prevent multiple artifacts"
fi
