# WMO/ICAO Web Validator
Web-based validator for WMO and ICAO XML schemas/models.  Utilizes the Crux library for XML Schema 1.0 and Schematron validation
of XML messages.  This is an HTTP-based Java toolkit with two components:

* a web user interface for validating messages
* a server-side component that can be used to support validation as a service.  This takes XML to validate in an HTTP POST
body and returns validation results in JSON.  This can be used to provide a long-running and performant validation service
using Crux - the JVM is only started once and reused elements are cached

# Running
This tool is a Java web application that can be run in any modern servlet container (Tomcat, Jetty, etc.).  The .war file
must be deployed into the servlet container, but the deployment method is different for each container.  For Tomcat,
the WAR file can be deployed by copying it into $CATALINA_HOME/webapps/ or by using the manager web user interface.

Runnable WAR files may be found through the [releases](/releases) page or may be built off of the master as described below.

# Web user interface
Once the tool is running, the web user interface can be loaded at the root web location (for example: http://localhost:8080/).
However, the HTTP port and path may differ depending on how the web container is configured.

# Automated validation
Once this tool has been deployed in a Java web container and the web container is running, HTTP POST requests are accepted
for validating XML messages.  The XML to be validated must be in the body of the HTTP POST request and results are returned
as JSON.

Internally this tool utilizes Crux to validate messages.  Crux will cache transformations on a per-thread basis such as
those used to perform Schematron validation, so Schematron validation will be slower the first time and much faster
subsequently.  Initial tests using multiple concurrent HTTP request threads suggest that 70+ combined XML schema/Schematron
validation requests per second can easily be reached with modern hardware.

Example validation request using curl for a file named metar-A3-1.xml:

  `curl -d @metar-A3-1.xml "http://localhost:8080/validate"`

# Building
Java 8+ and Maven 3 are required to build.  With these installed and in your path, run:

  `mvn clean package`

which will generate the .war file under the target/ sub-directory