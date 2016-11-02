<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta http-equiv="X-UA-Compatible" content="IE=edge">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <!-- The above 3 meta tags *must* come first in the head; any other head content must come *after* these tags -->
    <title>WMO/ICAO XML Validator</title>

    <!-- Bootstrap -->
    <link href="css/bootstrap.min.css" rel="stylesheet">

    <link rel="stylesheet" href="css/codemirror.css">
    <link rel="stylesheet" href="css/wiwv.css">

    <script src="js/codemirror.js"></script>
    <!-- jQuery (necessary for Bootstrap's JavaScript plugins) -->
    <script src="js/jquery.min.js"></script>
    <!-- Include all compiled plugins (below), or include individual files as needed -->
    <script src="js/bootstrap.min.js"></script>
    <!-- HTML5 shim and Respond.js for IE8 support of HTML5 elements and media queries -->
    <!-- WARNING: Respond.js doesn't work if you view the page via file:// -->
    <!--[if lt IE 9]>
    <script src="https://oss.maxcdn.com/html5shiv/3.7.3/html5shiv.min.js"></script>
    <script src="https://oss.maxcdn.com/respond/1.4.2/respond.min.js"></script>
    <![endif]-->
</head>

<body onload="init()">

<script>
    var highlightedLineMarkers = [];
    var codeMirrorEditor;

    function init(){
        $(document).ready(function() {
            codeMirrorEditor = CodeMirror.fromTextArea(document.getElementById('input-xml'), {
//                stylesheet:'monokai.css'
                mode: "application/xml",
//                styleActiveLine: true,
                lineNumbers: true,
                lineWrapping: true
            });
        });
    }

    function highlightLines(lineNumbers) {

        //Select editor loaded in the DOM
        // remove old highlights
        $.each( codeMirrorEditor.getAllMarks(), function( index, marker ){
            marker.clear();
        });

        $.each( lineNumbers, function( index, lineNumber ){
            //Line number is zero based index
            var actualLineNumber = lineNumber - 1;
            var marker = codeMirrorEditor.markText(
                    {line: actualLineNumber, ch:0},
                    {line: actualLineNumber, ch:10000},
                    { className: 'line-error' });
        });
    }

    function validateInput(){
        var input = $("#input-file");
        var text = codeMirrorEditor.getValue();
        var file = input.val();
        if (text != null && text != "" &&
            file != null && file != "" ) {
            showError( "Please enter either XML text OR an uploaded file" );
            return false;
        }else{
            showError( "" );
        }
    }

    //override the form processing
    function validateXML() {
        //re-enable if file browsing is re-enabled
        var validInput = validateInput();
        //if the input has problems don't proceed further with the button click
        if ( validInput == false ) return false;
        $( "#errors" ).empty();

        $( '#validate-button').button('loading');

        var xmlContent = codeMirrorEditor.getValue();

        if( xmlContent && xmlContent.length > 0 ) {
            sendValidationRequest( xmlContent );
        }
        //use intput file content
        else{
            if (!window.File || !window.FileReader || !window.FileList || !window.Blob) {
                showError('The file API is not fully supported by this browser.');
                return;
            }

            input = document.getElementById('input-file');
            if (!input) {
                console.log("Couldn't find the input-file element.");
            }
            else if (!input.files) {
                showError("This browser doesn't seem to support the `files` property of file inputs.");
            }
            else if (!input.files[0]) {
                alert("Please select a file before clicking 'Load'");
            }
            else {
                file = input.files[0];
                fr = new FileReader();
                fr.onload = function(fileName){
                    sendValidationRequest(fr.result);
                };
                fr.readAsText(file);
            }
        }
    }

    function sendValidationRequest( xmlContent ){
        var url = "./validate";
        $.ajax({
            url: url,
            method: "POST",

            data: xmlContent, // The data to send
            contentType: 'application/xml',

            dataType: "json" // The type of data we expect back
        })
            // Code to run if the request succeeds (is done)
                .done(function (result) {
                    $('#validate-button').button('reset');
                    $("#errors-row").empty();
                    var validationErrors = result.validationErrors;
                    var lines = [];
                    $.each(validationErrors, function (index, error) {
                        $("#errors-row").append('<div class="row col-sm-12 m-t bg-danger"><strong>Error on Line ' + error.line + ', Col ' + error.col + ':</strong><br> ' + error.error + '</div>');
                        lines.push(error.line);
                    });
                    if( !validationErrors || validationErrors.length == 0 ){
                        $("#errors-row").append('<div class="row h4 col-sm-12 m-t bg-success">XML is valid!</div>');
                    }
                    highlightLines(lines);
                    var messages = result.messages;
                    $.each(messages, function (index, message) {
                        $("#errors-row").append('<div class="row col-sm-12 m-t">&nbsp; \n</div>');
                        $("#errors-row").append('<div class="row col-sm-12 m-t bg-warning">' + message + '</div>');
                    });
                })
            // Code to run if the request fails; the raw request and
            // status codes are passed to the function
                .fail(function (xhr, status, errorThrown) {
                    $('#validate-button').button('reset');
                    $("#errors").append("Error when contacting server: '" + errorThrown + "'");
                });
    }

    function showError( errorStr ){
        console.log( errorStr );
        $( "#errors").html( errorStr );
    }

</script>

<div class="lead vertical-center">
    <div class="container text-center">
        <h1>WMO/ICAO XML Web Validator</h1>
        <img src="assets/ICAO-logo-square.jpg" class="img-circle" alt="ICAO" width="100" height="100">
        <img src="assets/WMO-logo-v2-square.jpg" class="img-circle" alt="WMO" width="100" height="100">
    </div>
</div>
<p>Welcome to the authoritative validation home for WMO and ICAO data models. Schemas
which are hosted from <a href="http://schemas.wmo.int">schemas.wmo.int</a> can be validated
here, including IWXXM, METCE, WMO Collect, and others.</p>
<p>This site uses local copies of XML Schema and Schematron files. XML files
with WMO, ICAO, ISO, and OGC namespaces should validate quickly without
any outgoing network connections.  This site uses the
authoritative XML validator library, <a href="https://github.com/NCAR/crux">Crux</a>, to validate messages.
Crux can also be used for validation as a Java library or can be used as a cross-platform
command-line utility.</p>
<h3>Validation tips</h3>
<p>Validated XML files should always include an <strong>xsi:schemaLocation</strong> attribute on the
root element</p>

<div name="xmlform">
    <div class="row">
        <!-- 8 out of 12 columns -->
        <div class="container col-md-8">
            <h2>XML</h2>

            <div class="form-group">
                <textarea class="small" id="input-xml" name="input-xml" rows="20" placeholder="Paste XML here"></textarea>
            </div>
            <div class="form-group">
                <label for="input-file">OR upload an XML file</label>
                <input type="file" class="file" id="input-file" name="input-file" aria-describedby="fileHelp" size="40">
            </div>

            <div id="errors" class="text-danger bg-danger text-center"></div>
            <div>&nbsp;</div>

            <button id="validate-button" class="btn btn-primary"
                    data-loading-text="<i class='fa fa-circle-o-notch fa-spin'></i> Validating..."
                    onclick="return validateXML()">Validate</button>
        </div>
        <!-- Validation errors, the 4 remaining columns -->
        <div class="container col-md-4" id="errors-panel">
            <div class="row">
                <h2>Result</h2>
            </div>
            <div id="errors-row" class="row"></div>
        </div>
    </div>
</div>

</body>
</html>
