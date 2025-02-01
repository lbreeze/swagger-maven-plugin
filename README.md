# Swagger Maven Plugin

This plugin is intended to use the [Swagger Core library](https://github.com/swagger-api/swagger-core) to generate
OpenAPI documentation from a JAX-RS based REST service with as little change as possible. This allows for @SwaggerDefinition, @ReaderListener and ModelConverters to work the same way as with the core Swagger library.


# Status

The plugin is considered production ready. The version 2.x.x of the plugin is supporting generation of OpenAPI version 3 specifications using Swagger 2.x. To generate OpenAPI version 2 specifications using Swagger 1.x use the latest 1.x.x version of the plugin.

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.lbreeze/swagger-maven-plugin/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.lbreeze/swagger-maven-plugin/)
[![Javadoc](https://javadoc.io/badge/io.github.lbreeze/swagger-maven-plugin/badge.svg)](https://www.javadoc.io/doc/io.github.lbreeze/swagger-maven-plugin)
[![Build status](https://travis-ci.org/openapi-tools/swagger-maven-plugin.svg?branch=master)](https://travis-ci.org/openapi-tools/swagger-maven-plugin)
[![Known Vulnerabilities](https://snyk.io/test/github/openapi-tools/swagger-maven-plugin/badge.svg)](https://snyk.io/test/github/openapi-tools/swagger-maven-plugin) 


# Usage

To have Swagger generate the OpenAPI specifications as part of the build add in the plugin to the POM.

```xml
<build>
  <plugins>
    ...
    <plugin>
      <groupId>io.github.lbreeze</groupId>
      <artifactId>swagger-maven-plugin</artifactId>
      <configuration>
        <resourcePackages>
          <resourcePackage>my.company.package.api</resourcePackage>
          <resourcePackage>my.company.package.api.v2</resourcePackage>
        </resourcePackages>
        <outputDirectory>${basedir}/target/</outputDirectory>
        <outputFilename>swagger</outputFilename>
        <outputFormats>JSON,YAML</outputFormats>
        <prettyPrint>true</prettyPrint>
        <lagom>true</lagom>
      </configuration>
      <executions>
        <execution>
          <goals>
            <goal>generate</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
    ...
  </plugins>
</build>
```

This will run the generation in the prepare-package lifecycle stage of the Maven build.

## Specifying Packages with JAX-RS Endpoints

The packages containing JAX-RS endpoints must be configured using the `resourcePackages` element. See the minimal configuration above.

## Lagom support

To add Lagom configuration support you have to set `lagom` property to `true`. When using fully swagger-compliant API definition you have to set it to `false`

## Properties of Swagger model

Most general properties of the Swagger model is configurable using the swaggerConfig element. Note this may also be configured through the @OpenAPIDefinition annotation - see [Customizing your auto-generated Swagger Definitions](http://swagger.io/customizing-your-auto-generated-swagger-definitions-in-1-5-x/).

```xml
<plugin>
  <groupId>io.github.lbreeze</groupId>
  <artifactId>swagger-maven-plugin</artifactId>
  <configuration>
    <swaggerConfig>
      <servers>
        <server>
          <url>https://services.exmple.it/base/path</url>
          <description>Endpoint URL</description>
        </server>
      </servers>
      <info>
        <title>Title</title>
        <version>1.0.0</version>
        <termsOfService>Terms</termsOfService>
        <contact>
          <email>e@mail.com</email>
          <name>My Name</name>
          <url>https://google.com</url>
        </contact>
        <license>
          <url>https://license</url>
          <name>MIT</name>
        </license>
        <extensions>
          <x-custom-field-1>my-custom-field-1</x-custom-field-1>
          <x-custom-field-2>my-custom-field-2</x-custom-field-2>
        </extensions>
      </info>
      <descriptionFile>src/test/resources/descriptions.md</descriptionFile>
    </swaggerConfig>
```


## Deploying

The generated OpenAPI specifications may be installed and deployed as Maven artifact. To enable this add the configuration parameter attachSwaggerArtifact.

```xml
<plugin>
  <groupId>io.github.lbreeze</groupId>
  <artifactId>swagger-maven-plugin</artifactId>
  <configuration>
    <attachSwaggerArtifact>true</attachSwaggerArtifact>
```

# Acknowledgement

Based on and forked from [OpenAPI Tools](https://github.com/openapi-tools/swagger-maven-plugin) swagger plugin.

Thanks to [Thor Anker Kvisgård Lange](https://github.com/langecode) for his contribution.
