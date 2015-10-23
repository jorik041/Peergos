arrow = function(from, to) {
    // initialize pointy
    to.pointy({
        pointer      : from,
        // additional class name added to the pointer & the arrow (canvas)
        // to add a z-index of 1
        defaultClass : 'zindex',
        // "pointy-active" class is used to keep the last updated pointer on top
        // this is the default value
        activeClass  : 'pointy-active',
        // arrow base width (in pixels)
        arrowWidth   : 30
    });
}

// String -> String
drawArrow = function(sourceClass, targetClass) {
    arrow($("."+sourceClass), $("."+targetClass));
}

tourStep = function(text, targetClass) {
    $(".tour-text").text(text);
    drawArrow("pointer", targetClass);
}

var tourElements = ["tour-home", "tour-upload", "tour-mkdir", "tour-view", "tour-social", "tour-logout"];
var tourText = ["Click here to go to your home directory.", "Click here to upload a file, or drag and drop one into the window.", "Click here to make a new directory", "Click here for social options. You can send a follow request, see your followers and see the files shared with you.", "Click here to logout"];

function showTour(index) {
    if (index != 0)
	tourStep(tourText[index-1], tourElements[index-1]); // hide the previous one
    $(".pointer").show();
    $(".pointy").show();
    tourStep(tourText[index], tourElements[index]);
}

function tourNext() {
    var current = tourText.indexOf($(".tour-text").text());
    if (current == tourElements.length-1)
	endTour();
    else
	showTour(current+1);
}

function endTour() {
    $(".pointer").hide();
    $(".pointy").hide();
}

function startTour() {
    showTour(0);
}
