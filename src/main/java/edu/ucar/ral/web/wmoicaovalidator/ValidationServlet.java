package edu.ucar.ral.web.wmoicaovalidator;

import edu.ucar.ral.crux.SchematronValidator;
import edu.ucar.ral.crux.ValidationError;
import edu.ucar.ral.crux.ValidationException;
import edu.ucar.ral.crux.XML10Validator;
import net.sf.saxon.s9api.SaxonApiException;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This servlet takes XML in the HTTP POST body runs XML schema and Schematron validation.
 * After validation is complete the results are returned in JSON format
 *
 * Local XML schema/Schematron file locations can be indicated via an environment variable: $SCHEMA_BUNDLE_DIR.
 * This directory should have a catalog.xml file with local schema locations
 */
@WebServlet(
  name = "ValidationServlet", urlPatterns = { "/validate" }
)
public class ValidationServlet extends HttpServlet {
  private static final String ENV_SCHEMA_BUNDLE_DIR = "SCHEMA_BUNDLE_DIR";

  private XML10Validator xml10Validator = null;  //lazily created
  private SchematronValidator schematronValidator = null; //lazily created.  when null the validators have not been initialized yet
  private String localSchemaBundleDir = null;  //location of locally-cached schema and Schematron files

  @Override
  protected void doPost( HttpServletRequest req, HttpServletResponse res )
    throws ServletException, IOException {
    String xml = null;
    InputStream is = req.getInputStream();
    if( is != null ){
      xml = new BufferedReader(new InputStreamReader(is))
        .lines().collect( Collectors.joining( "\n" ));
    }
    List<String> userMessages = new ArrayList<>();

    System.out.printf( "Found XML to validate.  String length: %d\n", xml.length() );

    File tmpFile = File.createTempFile( "validation-tmp", ".xml" );
    //write the string to a local file
    PrintWriter out = new PrintWriter( tmpFile );
    out.write( xml );
    out.close();

    if( xml10Validator == null ) {
      initValidators();
    }

    List<ValidationError> errors = new ArrayList<>();
    try {
      userMessages.add( "Validated against XML Schema 1.0" );
      System.out.println( "Validating against XML Schema 1.0" );
      xml10Validator.validate( tmpFile.toString() );
    }
    catch( Exception e ) {
      if( e instanceof ValidationException ) {
        printValidationErrors( (ValidationException)e );
        //remove any machine-identifiable information, like directory paths
        for( ValidationError error : ( (ValidationException) e ).getValidationErrors()){
          if( error.getError().contains( localSchemaBundleDir )){
            ValidationError newError = new ValidationError(
              error.getError().replace( localSchemaBundleDir, "" ),
              "", error.getLineNumber(), error.getColumnNumber() );
            errors.add( newError );
          }
          else if( error.getError().contains( "file:" ) ){
            ValidationError newError = new ValidationError( "Internal Server Error", "", error.getLineNumber(), error.getColumnNumber() );
            errors.add( newError );
          }
          else {
            errors.add( error );
          }
        }
      }
      else {
        e.printStackTrace();
        errors.add( new ValidationError( "Internal Server Error", null, null, null ) );
      }
    }

    //perform Schematron validation
    try {
      //first search for WMO namespaces to find the model name and version of the Schematron rules to use
      SAXParserFactory spf = SAXParserFactory.newInstance();
      spf.setNamespaceAware( true );
      SAXParser saxParser = spf.newSAXParser();
      XMLReader xmlReader = saxParser.getXMLReader();
      NamespaceGrabber grabber = new NamespaceGrabber();
      xmlReader.setContentHandler( grabber );
      xmlReader.parse( new InputSource( new FileReader( tmpFile ) ) );
      if( grabber.rootNamespace.split( "\\/" ).length < 5 ) {
        String message = String.format( "Namespace %s could not be split into WMO/ICAO model name and version.  NOT running Schematron\n", grabber.rootNamespace );
        System.out.printf( message );
        userMessages.add( message );
      }
      else {
        String[] namespaces;
        if( grabber.rootIsCollection ){
          namespaces = new String[]{ grabber.rootNamespace, grabber.memberNamespace };
        }
        else{
          namespaces = new String[]{ grabber.rootNamespace };
        }
        for( String namespace : namespaces ) {
          String[] split = namespace.split( "\\/" );
          String schemaName = split[ 3 ];
          String version = split[ 4 ];

          String schematronFile = getSchematronFilePath( schemaName, version );
          File schematronFileObj = new File( schematronFile );
          if( schematronFileObj.exists() ) {
            System.out.println( "Validating against Schematron " + schematronFileObj.getAbsoluteFile() );
            userMessages.add( String.format( "Validated against Schematron for %s %s (rule/%s/%s.sch)", schemaName.toUpperCase(), version, version, schemaName ) );
            schematronValidator.validate( tmpFile.toString(), schematronFile );
          }
          else {
            userMessages.add( String.format( "Could not locate an appropriate Schematron file for %s.  No Schematron validation was performed", namespace ) );
          }
        }
      }
    }
    catch( Exception e ) {
      if( e instanceof ValidationException ) {
        printValidationErrors( (ValidationException)e );
        errors.addAll( ( (ValidationException) e ).getValidationErrors() );
      }
      else if( e instanceof IOException && e.getCause() instanceof SaxonApiException ){
        e.printStackTrace();
        IOException ioe = (IOException)e;
        SaxonApiException saxonApiException = (SaxonApiException)ioe.getCause();
        errors.add( new ValidationError( saxonApiException.getMessage(), null, saxonApiException.getLineNumber(), null ) );
      }
      else {
        e.printStackTrace();
        errors.add( new ValidationError( "Internal Server Error", null, null, null ) );
      }
    }

    tmpFile.delete();

    returnResults( req, res, xml, userMessages, errors );
  }

  private String getSchematronFilePath( String schemaName, String version ){
    return String.format( "%s/%s/%s.sch", localSchemaBundleDir + "/rule/", version, schemaName );
  }

  private void returnResults( HttpServletRequest req, HttpServletResponse res,
                              String xml, List<String> userMessages, List<ValidationError> validationErrors ) throws IOException {
    res.setContentType( "application/json" );

    StringBuilder builder = new StringBuilder();
    builder.append( "{" );

    if( userMessages.size() > 0 ) {
      builder.append( "\"messages\": [" );

      builder.append( String.join( ",", userMessages.stream().map( s -> "\""+s+"\"" ).collect( Collectors.toList()) ) );
      builder.append( "]" );
    }

    if( validationErrors.size() > 0 ) {
      builder.append( ",\"validationErrors\": [" );
      for( int i = 0; i < validationErrors.size(); i++ ){
        if( i != 0 ) builder.append( "," );
        ValidationError error = validationErrors.get( i );
        builder.append( "{\"error\": \""+sanitizeJSONString( error.getError() ) + "\"");
        if( error.getLineNumber() != null ){
          builder.append(", \"line\": "+error.getLineNumber() );
        }
        if( error.getColumnNumber() != null ){
          builder.append(", \"col\": "+error.getColumnNumber() );
        }
        builder.append( "}" );
      }
      builder.append( "]" );
    }

    builder.append( "}" );
    PrintWriter writer = res.getWriter();
    writer.write( builder.toString() );
    writer.close();
  }

  /**
   * Remove special JSON characters like double quotes from a JSON string
   * @param str
   * @return
   */
  private String sanitizeJSONString( String str ){
    return str.replace( "\"", "" ).replace( "<", "&lt;").replace( ">", "&gt" );  //remove double quotes
  }

  private void initValidators() throws IOException {//search for the envvar
    localSchemaBundleDir = System.getenv( ENV_SCHEMA_BUNDLE_DIR );
    if( localSchemaBundleDir == null ){
      //for Elastic Beanstalk Tomcat environment support
      localSchemaBundleDir = System.getProperty( ENV_SCHEMA_BUNDLE_DIR );
    }
    File catalogFile;
    if( localSchemaBundleDir == null ) {
      catalogFile = new File( "catalog.xml" );
      if( catalogFile.exists() ) {
        localSchemaBundleDir = new File( "." ).getCanonicalPath();
        System.out.printf( "Could not find env var $%s with local schema dir, but found catalog at %s\n", ENV_SCHEMA_BUNDLE_DIR, catalogFile.getAbsolutePath() );
      }
      else {
        System.out.printf( "Could not find env var $%s or local schema dir %s.  Not using a catalog file!!\n", ENV_SCHEMA_BUNDLE_DIR, catalogFile.getAbsolutePath() );
        localSchemaBundleDir = null;
        catalogFile = null;
      }
    }
    else {
      File schemaBundleDir = new File( localSchemaBundleDir );
      catalogFile = new File( schemaBundleDir, "catalog.xml" );
      if( schemaBundleDir.exists() && schemaBundleDir.isDirectory() && catalogFile.exists() ) {
        System.out.println( "Found env var $" + ENV_SCHEMA_BUNDLE_DIR + " with local schema dir location: " + localSchemaBundleDir );
      }
      else {
        System.out.println( "Found env var $" + ENV_SCHEMA_BUNDLE_DIR + " with local schema dir location: " +
          localSchemaBundleDir + ", but it either doesn't exist, isn't a directory, or doesn't have a catalog.xml file in it.  Not using a catalog file!!" );
        localSchemaBundleDir = null;
        catalogFile = null;
      }
    }
    xml10Validator = new XML10Validator( 0, catalogFile != null ? catalogFile.toString() : "" );
    schematronValidator = new SchematronValidator();
  }

  private void printValidationErrors( ValidationException e ){
    System.out.println( "Validation FAILED: ");
    for( ValidationError err : e.getValidationErrors() ){
      System.out.printf( "ERROR%s%s: %s\n",
        err.getLineNumber() != null ? " on line " + err.getLineNumber() : "",
        err.getColumnNumber() != null ? ", col " + err.getColumnNumber() : "",
        err.getError() );
    }
  }

  /**
   * A SAX ContentHandler for grabbing the namespace of the top-level element parsed.
   * Only the first element encountered is parsed, UNLESS the top level element is a collection
   * (i.e., WMO Collect) rootNamespace in which case the member rootNamespace is also gathered
   */
  private class NamespaceGrabber implements ContentHandler {
    private boolean done = false;  //true when we've found the top-level element rootNamespace
    private boolean rootIsCollection = false;  //true when the root element is a WMO Collect element
    private String rootNamespace = null;
    private String memberNamespace = null;  //only present when rootIsCollection is true

    @Override
    public void startElement( String namespaceUri, String localName, String qName, Attributes atts ) throws SAXException {
      if( done ) return;

      //if this is the root namespace, grab it
      if( rootNamespace == null ) {
        rootNamespace = namespaceUri;
        if( rootNamespace.contains( "/collect" ) ) {
          rootIsCollection = true;
        }
        else {
          done = true;
        }
      }
      //otherwise we are looking for the first non-collect child
      else if( !namespaceUri.contains( "/collect" ) ){
        memberNamespace = namespaceUri;
        done = true;
      }
      //otherwise keep looking
    }

    @Override
    public void setDocumentLocator( Locator locator ) {}

    @Override
    public void startDocument() throws SAXException {}

    @Override
    public void endDocument() throws SAXException {}

    @Override
    public void startPrefixMapping( String prefix, String uri ) throws SAXException {}

    @Override
    public void endPrefixMapping( String prefix ) throws SAXException {}

    @Override
    public void endElement( String uri, String localName, String qName ) throws SAXException {}

    @Override
    public void characters( char[] ch, int start, int length ) throws SAXException {}

    @Override
    public void ignorableWhitespace( char[] ch, int start, int length ) throws SAXException {}

    @Override
    public void processingInstruction( String target, String data ) throws SAXException {}

    @Override
    public void skippedEntity( String name ) throws SAXException {}
  }
}