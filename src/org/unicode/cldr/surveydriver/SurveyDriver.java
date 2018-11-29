package org.unicode.cldr.surveydriver;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.logging.Level;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.logging.Logs;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * Perform automated testing of the CLDR Survey Tool using Selenium WebDriver.
 * Reference: https://unicode.org/cldr/trac/ticket/11488
 *   "Implement new Survey Tool automated test framework and infrastructure"
 *
 * This test has been used with the survey-driver project running in Eclipse. At the same time,
 * cldr-apps can be running either on localhost (in the same Eclipse as survey-driver) or on SmokeTest.
 *
 * This code requires installing an implementation of WebDriver, such as chromedriver for Chrome.
 * On macOS, chromedriver can be installed from Terminal with brew as follows:
 *   brew tap homebrew/cask
 *   brew cask install chromedriver
 * -- or download chromedriver from https://chromedriver.storage.googleapis.com
 * (Testing with geckodriver for Firefox has been unsuccessful.)
 *
 * A tutorial for setting up a project using Selenium in Eclipse:
 *   https://www.guru99.com/selenium-tutorial.html
 */
public class SurveyDriver {
	/*
	 * Enable/disable specific tests using these booleans
	 */
	static final boolean TEST_FAST_VOTING = true;
	static final boolean TEST_LOCALES_AND_PAGES = false;
	static final boolean TEST_ANNOTATION_VOTING = false;

	/*
	 * Configure for Survey Tool server, which can be localhost, SmokeTest, or other
	 */
	static final String BASE_URL = "http://localhost:8080/cldr-apps/";
	// static final String BASE_URL = "http://cldr-smoke.unicode.org/smoketest/";

	/*
	 * Configure login, which may depend on BASE_URL.
	 * TODO: enable multiple distinct logins for the same server, so that each node in the grid can
	 * run as a different user. Probably should use configuration files instead of hard-coding here.
	 */
	static final String LOGIN_URL = "survey?letmein=pTFjaLECN&email=admin@";
	// static final String LOGIN_URL = "survey?email=hinarlinguist.wul7q2qkq@dbi4.utilika%20foundation.example.com&uid=2by_67IPy";

	static final long TIME_OUT_SECONDS = 30;
	static final long SLEEP_MILLISECONDS = 100;

	/*
	 * If USE_REMOTE_WEBDRIVER is true, then the driver will be a RemoteWebDriver (a class that implements
	 * the WebDriver interface). Otherwise the driver could be a ChromeDriver, or FirefoxDriver, EdgeDriver,
	 * or SafariDriver (all subclasses of RemoteWebDriver) if we add options for those.
	 * While much of the code in this class works either way, Selenium Grid needs the driver to be a
	 * RemoteWebDriver and requires installation of a hub and one or more nodes.
	 *
	 * Sample commands to start the grid (first node is default port 5555, second node explicit port 5556):
	 *
	 * HUB_URL=http://localhost:4444/grid/register java -jar selenium-server-standalone-3.9.1.jar -role hub
	 * HUB_URL=http://localhost:4444/grid/register java -jar selenium-server-standalone-3.9.1.jar -role node
	 * HUB_URL=http://localhost:4444/grid/register java -jar selenium-server-standalone-3.9.1.jar -role node -port 5556
	 */
	static final boolean USE_REMOTE_WEBDRIVER = false;

	WebDriver driver;
	WebDriverWait wait;

	public static void main(String[] args) {
		SurveyDriver s = new SurveyDriver();
		s.setUp();
		if (TEST_FAST_VOTING) {
			s.testFastVoting();
		}
		if (TEST_LOCALES_AND_PAGES) {
			s.testAllLocalesAndPages();
		}
		if (TEST_ANNOTATION_VOTING) {
			s.testAnnotationVoting();
		}
		s.tearDown();
	}

	/**
	 * Set up the driver and its "wait" object.
	 */
	private void setUp() {
		LoggingPreferences logPrefs = new LoggingPreferences();
		logPrefs.enable(LogType.BROWSER, Level.ALL);

		ChromeOptions options = new ChromeOptions();
		options.setCapability(CapabilityType.LOGGING_PREFS, logPrefs);
		options.addArguments("start-maximized"); // doesn't work
		// options.addArguments("auto-open-devtools-for-tabs"); // this works, but makes window too narrow

		if (USE_REMOTE_WEBDRIVER) {
			try {
				driver = new RemoteWebDriver(new URL("http://localhost:4444/wd/hub"), options);
			} catch (MalformedURLException e) {
				System.out.println(e);
			}
			System.out.println("Session id = " + ((RemoteWebDriver) driver).getSessionId());
		} else {
			driver = new ChromeDriver(options);
			// driver.manage().window().maximize(); // doesn't work
		}
		wait = new WebDriverWait(driver, TIME_OUT_SECONDS, SLEEP_MILLISECONDS);
	}

	/**
	 * Clean up when finished testing.
	 */
	private void tearDown() {
		if (driver != null) {
			/*
			 * This five-second sleep may not always be appropriate. It can help to see the browser for a few seconds
			 * before it closes. Alternatively a breakpoint can be set on driver.quit() for the same purpose.
			 */
			try {
				Thread.sleep(5000);
			} catch (Exception e) {
				System.out.println("Sleep interrupted before driver.quit; " + e);
			}
			driver.quit();
		}
	}

	/**
	 * Test "fast" voting, that is, voting for several items on a page, and measuring
	 * the time of response.
	 *
	 * Purposes:
	 * (1) study the sequence of events, especially client-server traffic,
	 * when multiple voting events (maybe by a single user) are being handled;
	 * (2) simulate simultaneous input from multiple vetters, for integration and
	 * performance testing under high load.
	 *
	 * References:
	 * https://cldr.unicode.org/index/cldr-engineer/sow "Performance Improvement Goals"
	 * https://unicode.org/cldr/trac/ticket/11211 "Performance is slow when voting on multiple items on a page"
	 * https://unicode.org/cldr/trac/ticket/10990 "Fix synchronize (threading)"
	 */
	private boolean testFastVoting() {
		if (!login()) {
			return false;
		}
		/*
		 * TODO: configure the locale and page on a per-node basis, to enable multiple simulated
		 * users to be voting in multiple locales and/or pages. 
		 */
		String loc = "ar";
		// String loc = "en_CA";
		String page = "Languages_A_D";
		String url = BASE_URL + "v#/" + loc + "/" + page;

		/*
		 * Repeat the test for a minute or so.
		 * Eventually we'll have more sophisticated criteria for when to stop.
		 * This loop to 1000 isn't set in stone.
		 */
		for (int i = 0; i < 1000; i++) {
			try {
				if (!testFastVotingInner(page, url)) {
					return false;
				}
			} catch (StaleElementReferenceException e) {
				/*
				 * Sometimes we get "org.openqa.selenium.StaleElementReferenceException:
				 * stale element reference: element is not attached to the page document".
				 * Presumably this happens due to ajax response causing the table to be rebuilt.
				 * TODO: catch this exception and continue wherever it occurs. Ideally also survey.js
				 * may be revised to update the table in place when possible instead of rebuilding the
				 * table from scratch so often. Reference: https://unicode.org/cldr/trac/ticket/11571
				 */
				System.out.println("Continuing main loop after StaleElementReferenceException, i = " + i);
				continue;
			}
		}
		System.out.println("✅ Fast vote test passed for " + loc + ", " + page);
		return true;
	}

	private boolean testFastVotingInner(String page, String url) {
		driver.get(url);
		/*
		 * Wait for the correct title, and then wait for the div
		 * whose id is "LoadingMessageSection" to get the style "display: none".
		 */
		if (!waitForTitle(page, url)) {
			return false;
		}
		if (!waitUntilLoadingMessageDone(url)) {
			return false;
		}
		if (!hideLeftSidebar(url)) {
			return false;
		}
		if (!waitUntilElementInactive("left-sidebar", url)) {
			return false;
		}
		/*
		 * TODO: handle "overlay" element more robustly. While this mostly works, the overlay can
		 * pop up again when you least expect it, causing, for example:
		 *
		 * org.openqa.selenium.WebDriverException: unknown error: Element <input ...
		 * title="click to vote" value="الأدانجمية"> is not clickable at point (746, 429).
		 * Other element would receive the click: <div id="overlay" class="" style="z-index: 1000;"></div>
		 */
		if (!waitUntilElementInactive("overlay", url)) {
			return false;
		}
		double firstClickTime = 0;

		/*
		 * For the first four rows, click on the Abstain (nocell) button.
		 * Then, for the first three rows, click on the Winning (proposedcell) button.
		 * Then, for the fourth row, click on the Add (addcell) button and enter a new value.
		 * TODO: instead of hard-coding these hexadecimal row ids, specify the first four
		 * "tr" elements of the main table using the appropriate findElement(By...).
		 * Displayed rows depend on coverage level, which in turn depends on the user; if we're logged
		 * in as Admin, then we see Comprehensive; if not logged in, we see Modern (and we can't vote).
		 * Something seems to have changed between versions 34 and 35; now first four rows are:
		 *     Abkhazian ► ab	r@f3d4397b739b287
		 * 	   Achinese ► ace	r@6899b21f19eef8cc
		 * 	   Acoli ► ach		r@1660459cc74c9aec
		 * 	   Adangme ► ada	r@7d1d3cbd260601a4
		 * Acoli appears to be a new addition.
		 */
		String[] rowIds = { "f3d4397b739b287", "6899b21f19eef8cc", "1660459cc74c9aec", "7d1d3cbd260601a4" };
		String[] cellIds = { "nocell", "proposedcell" };
		for (String cell : cellIds) {
			for (int i = 0; i < rowIds.length; i++) {
				String rowId = "r@" + rowIds[i];
				boolean doAdd = (i == rowIds.length - 1) && cell.equals("proposedcell");
				String cellId = doAdd ? "addcell" : cell;
				WebElement rowEl = null, columnEl = null, clickEl = null;
				try {
					rowEl = driver.findElement(By.id(rowId));
				} catch (Exception e) {
					System.out.println(e);
				}
				if (rowEl == null) {
					System.out.println("❌ Fast vote test failed, missing row id " + rowId + " for " + url);
					return false;
				}
				try {
					columnEl = rowEl.findElement(By.id(cellId));
				} catch (Exception e) {
					System.out.println(e);
				}
				if (columnEl == null) {
					System.out
							.println("❌ Fast vote test failed, no column " + cellId + " for row " + rowId + " for "
									+ url);
					return false;
				}
				String tagName = doAdd ? "button" : "input";
				try {
					clickEl = columnEl.findElement(By.tagName(tagName));
				} catch (StaleElementReferenceException e) {
					System.out.println("Continuing after StaleElementReferenceException for findElement by tagName "
							+ rowId + " for " + url);
					continue;
				} catch (Exception e) {
					System.out.println(e);
				}
				if (clickEl == null) {
					System.out.println(
							"❌ Fast vote test failed, no tag " + tagName + " for row " + rowId + " for " + url);
					return false;
				}
				clickEl = waitUntilRowCellTagElementClickable(clickEl, rowId, cellId, tagName, url);
				if (clickEl == null) {
					return false;
				}
				try {
					wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("overlay")));
				} catch (Exception e) {
					System.out.println(e);
					System.out.println("❌ Fast vote test failed, invisibilityOfElementLocated overlay for row " + rowId
							+ " for " + url);
					return false;
				}
				if (firstClickTime == 0.0) {
					firstClickTime = System.currentTimeMillis();
				}
				try {
					clickOnRowCellTagElement(clickEl, rowId, cellId, tagName, url);
				} catch (Exception e) {
					System.out.println(e);
					System.out.println("❌ Fast vote test failed, clickEl.click for row " + rowId + " for " + url);
					return false;
				}
				if (doAdd) {
					WebElement inputEl = waitInputBoxAppears(rowEl, url);
					if (inputEl == null) {
						System.out.println("❌ Fast vote test failed, didn't see input box for " + url);
						return false;
					}
					inputEl = waitUntilRowCellTagElementClickable(inputEl, rowId, cellId, "input", url);
					if (inputEl == null) {
						System.out.println("❌ Fast vote test failed, input box not clickable for " + url);
						return false;
					}
					inputEl.clear();
					inputEl.click();
					inputEl.sendKeys("Testxyz");
					inputEl.sendKeys(Keys.RETURN);
				}
			}
		}
		/*
		 * TODO: Wait for tr_checking2 element (temporary green background) to exist.
		 * Problem: sometimes server is too fast and/or our polling isn't frequent enough,
		 * and then the tr_checking2 element(s) are already gone before we get here.
		 * For now, skip this call.
		 */
		if (false && !waitUntilClassChecking(true, url)) {
			return false;
		}
		/*
		 * Wait for tr_checking2 element (temporary green background) NOT to exist.
		 * The temporary green background indicates that the client is waiting for a response
		 * from the server before it can update the display to show the results of a
		 * completed voting operation.
		 */
		if (!waitUntilClassChecking(false, url)) {
			return false;
		}
		double deltaTime = System.currentTimeMillis() - firstClickTime;
		System.out.println("Total time elapsed since first click = " + deltaTime / 1000.0 + " sec");
		return true;
	}

	/**
	 * Log into Survey Tool.
	 */
	private boolean login() {
		String url = BASE_URL + LOGIN_URL;
		driver.get(url);

		/*
		 * Wait for the correct title, and then wait for the div
		 * whose id is "LoadingMessageSection" to get the style "display: none".
		 */
		String page = "Locale List";
		if (!waitForTitle(page, url)) {
			return false;
		}
		if (!waitUntilLoadingMessageDone(url)) {
			return false;
		}
		return true;
	}

	/**
	 * Test all the locales and pages we're interested in.
	 */
	private void testAllLocalesAndPages() {
		String[] locales = SurveyDriverData.getLocales();
		String[] pages = SurveyDriverData.getPages();

		/*
		 * Reference: https://unicode.org/cldr/trac/ticket/11238 "browser console shows error message,
		 * there is INHERITANCE_MARKER without inheritedValue"
		 */
		String searchString = "INHERITANCE_MARKER without inheritedValue"; // formerly, "there is no Bailey Target item"

		for (String loc : locales) {
			// for (PathHeader.PageId page : PathHeader.PageId.values()) {
			for (String page : pages) {
				if (!testOneLocationAndPage(loc, page, searchString)) {
					return;
				}
			}
		}
	}

	/**
	 * Test the given locale and page.
	 *
	 * @param loc
	 *            the locale string, like "pt_PT"
	 * @param page
	 *            the page name, like "Alphabetic_Information"
	 * @return true if all parts of the test pass, else false
	 */
	private boolean testOneLocationAndPage(String loc, String page, String searchString) {
		String url = BASE_URL + "v#/" + loc + "/" + page;
		driver.get(url);

		/*
		 * Wait for the correct title, and then wait for the div
		 * whose id is "LoadingMessageSection" to get the style "display: none".
		 */
		if (!waitForTitle(page, url)) {
			return false;
		}
		if (!waitUntilLoadingMessageDone(url)) {
			return false;
		}
		int searchStringCount = countLogEntriesContainingString(searchString);
		if (searchStringCount > 0) {
			System.out.println("❌ Test failed: " + searchStringCount + " occurrences in log of \'" + searchString
					+ "\' for " + url);
			return false;
		}
		System.out.println(
				"✅ Test passed: zero occurrences in log of \'" + searchString + "\' for " + loc + ", " + page);
		return true;
	}

	private void testAnnotationVoting() {
		/*
		 * This list of locales was obtained by putting a breakpoint on SurveyAjax.getLocalesSet, getting its
		 * return value, and adding quotation marks by search/replace.
		 */
		String[] locales = SurveyDriverData.getLocales();
		String[] pages = SurveyDriverData.getAnnotationPages();

		/*
		 * Reference: https://unicode.org/cldr/trac/ticket/11270
		 * "Use floating point instead of integers for vote counts"
		 * See VoteResolver.java
		 *
		 * Note: This searchString didn't actually occur in the browser console, only in the
		 * Eclipse console, which is NOT detected by WebDriver. It didn't matter since I had
		 * a breakpoint in Eclipse for the statement in question.
		 */
		String searchString = "Rounding matters for useKeywordAnnotationVoting";
		long errorCount = 0;
		for (String loc : locales) {
			// for (PathHeader.PageId page : PathHeader.PageId.values()) {
			for (String page : pages) {
				if (!testOneLocationAndPage(loc, page, searchString)) {
					/*
					 * We could return when we encounter an error.
					 * Or, we could keep going, to find more errors.
					 * Maybe there should be a global setting for that...
					 */
					// return;
					++errorCount;
				}
			}
		}
		if (errorCount > 0) {
			System.out.println("❌ Test failed, total " + errorCount + " errors");
		}
	}

	/**
	 * Count how many log entries contain the given string.
	 *
	 * @param searchString
	 *            the string for which to search
	 * @return the number of occurrences
	 */
	private int countLogEntriesContainingString(String searchString) {
		int searchStringCount = 0;
		Logs logs = driver.manage().logs();
		for (String type : logs.getAvailableLogTypes()) {
			List<LogEntry> logEntries = logs.get(type).getAll();
			for (LogEntry entry : logEntries) {
				String message = entry.getMessage();
				if (message.contains(searchString)) {
					System.out.println(entry);
					++searchStringCount;
				}
			}
		}
		return searchStringCount;
	}

	/**
	 * Wait for the title to contain the given string
	 *
	 * @param s the string expected to occur in the title
	 * @param url the url we're loading
	 * @return true for success, false for failure
	 */
	private boolean waitForTitle(String s, String url) {
		try {
			wait.until(new ExpectedCondition<Boolean>() {
				@Override
				public Boolean apply(WebDriver webDriver) {
					return (webDriver.getTitle().contains(s));
				}
			});
		} catch (Exception e) {
			System.out.println(e);
			System.out.println("❌ Test failed, maybe timed out, waiting for title to contain " + s + " in " + url);
			return false;
		}
		return true;
	}

	/**
	 * Wait for the div whose id is "LoadingMessageSection" to get the style "display: none".
	 *
	 * @param url the url we're loading
	 * @return true for success, false for failure
	 */
	private boolean waitUntilLoadingMessageDone(String url) {
		String loadingId = "LoadingMessageSection";
		try {
			wait.until(new ExpectedCondition<Boolean>() {
				@Override
				public Boolean apply(WebDriver webDriver) {
					return webDriver.findElement(By.id(loadingId)).getCssValue("display").contains("none");
				}
			});
		} catch (Exception e) {
			System.out.println(e);
			System.out.println("❌ Test failed, maybe timed out, waiting for " + loadingId + " in " + url);
			return false;
		}
		return true;
	}

	/**
	 * Hide the element whose id is "left-sidebar", by simulating the appropriate mouse action if it's
	 * visible.
	 *
	 * @param url the url we're loading
	 * @return true for success, false for failure
	 */
	private boolean hideLeftSidebar(String url) {
		String id = "left-sidebar";
		WebElement bar = driver.findElement(By.id(id));
		if (bar.getAttribute("class").contains("active")) {
			/*
			 * Move the mouse away from the left sidebar.
			 * Mouse movements don't seem able to hide the left sidebar with WebDriver.
			 * Even clicks on other elements don't work.
			 * The only solution I've found is to add this line to redesign.js:
			 *     $('#dragger').click(hideOverlayAndSidebar);
			 * Then clicking on the edge of the sidebar closes it, and simulated click here works.
			 */
			// System.out.println("Moving mouse, so to squeak...");
			String otherId = "dragger";
			WebElement otherEl = driver.findElement(By.id(otherId));
			if (!waitUntilElementClickable(otherEl, url)) {
				return false;
			}
			try {
				otherEl.click();
			} catch (Exception e) {
				System.out.println("Exception caught while moving mouse, so to squeak...");
				System.out.println(e);
				return false;
			}
			/*
			 * With latest redesign.js on localhost, the above click triggers hideOverlayAndSidebar.
			 * With older code still on SmokeTest, however, that doesn't work, in which case the following
			 * executeScript works, though it's "cheating" since it doesn't simulate a GUI interaction.
			 */
			JavascriptExecutor js = (JavascriptExecutor) driver;
			js.executeScript("hideOverlayAndSidebar()");
		}
		return true;
	}

	/**
	 * Wait for the element with given id not to have class "active".
	 *
	 * @param id the id of the element
	 * @param url the url we're loading
	 * @return true for success, false for failure
	 */
	private boolean waitUntilElementInactive(String id, String url) {
		try {
			wait.until(new ExpectedCondition<Boolean>() {
				@Override
				public Boolean apply(WebDriver webDriver) {
					WebElement el = webDriver.findElement(By.id(id));
					return el == null || !el.getAttribute("class").contains("active");
				}
			});
		} catch (Exception e) {
			System.out.println(e);
			System.out.println("❌ Test failed, maybe timed out, waiting for " + id + " in " + url);
			return false;
		}
		return true;
	}

	/**
	 * Wait for an input element to appear inside the addcell in the given row element
	 *
	 * @param rowEl the row element that will contain the input element
	 * @param url the url we're loading
	 * @return the input element, or null for failure
	 */
	private WebElement waitInputBoxAppears(WebElement rowEl, String url) {
		/*
		 * Caution: a row may have more than one input element -- for example, the "radio" buttons are input elements.
		 * First we need to find the addcell for this row, then find the input tag inside the addcell.
		 * Note that the add button does NOT contain the input tag, but the addcell contains both the add button
		 * and the input tag.
		 */
		WebElement addCell = rowEl.findElement(By.id("addcell"));
		WebElement inputEl = null;
		try {
			inputEl = wait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(addCell, By.tagName("input")));
		} catch (Exception e) {
			System.out.println(e);
			System.out.println("❌ Test failed, maybe timed out, waiting for input in addcell in " + url);
		}
		return inputEl;
	}

	/**
	 * Wait until the element is clickable.
	 *
	 * @param el the element
	 * @param url the url we're loading
	 * @return true for success, false for failure
	 */
	private boolean waitUntilElementClickable(WebElement el, String url) {
		try {
			wait.until(ExpectedConditions.elementToBeClickable(el));
		} catch (Exception e) {
			System.out.println(e);
			System.out.println("❌ Test failed, maybe timed out, waiting for " + el + " to be clickable in " + url);
			return false;
		}
		return true;
	}

	/**
	 * Wait until the element specified by rowId, cellId, tagName is clickable.
	 *
	 * @param clickEl
	 * @param rowId
	 * @param cellId
	 * @param tagName
	 * @param url the url we're loading
	 * @return the (possibly updated) clickEl for success, null for failure
	 */
	private WebElement waitUntilRowCellTagElementClickable(WebElement clickEl, String rowId, String cellId, String tagName,
			String url) {
		int repeats = 0;
		for (;;) {
			try {
				wait.until(ExpectedConditions.elementToBeClickable(clickEl));
				return clickEl;
			} catch (StaleElementReferenceException e) {
				if (++repeats > 4) {
					break;
				}
				System.out.println("waitUntilRowCellTagElementClickable repeating for StaleElementReferenceException");
				WebElement rowEl = driver.findElement(By.id(rowId));
				WebElement columnEl = rowEl.findElement(By.id(cellId));
				clickEl = columnEl.findElement(By.tagName(tagName));
			} catch (Exception e) {
				System.out.println(e);
				break;
			}
		}
		System.out.println(
				"❌ Test failed in waitUntilRowCellTagElementClickable for " + rowId + "," + cellId + "," + tagName
						+ " in " + url);
		return null;
	}

	/**
	 * Click on the element specified by rowId, cellId, tagName.
	 *
	 * @param clickEl
	 * @param rowId
	 * @param cellId
	 * @param tagName
	 * @param url the url we're loading
	 * @return true for success, false for failure
	 */
	private boolean clickOnRowCellTagElement(WebElement clickEl, String rowId, String cellId, String tagName,
			String url) {
		int repeats = 0;
		for (;;) {
			try {
				clickEl.click();
				return true;
			} catch (StaleElementReferenceException e) {
				if (++repeats > 4) {
					break;
				}
				System.out.println("clickOnRowCellTagElement repeating for StaleElementReferenceException for " + rowId
						+ "," + cellId + "," + tagName + " in " + url);
				int recreateStringCount = countLogEntriesContainingString("insertRows: recreating table from scratch");
				System.out.println("clickOnRowCellTagElement: log has " + recreateStringCount + " scratch messages");
				WebElement rowEl = driver.findElement(By.id(rowId));
				WebElement columnEl = rowEl.findElement(By.id(cellId));
				clickEl = columnEl.findElement(By.tagName(tagName));
			} catch (Exception e) {
				System.out.println(e);
				break;
			}
		}
		System.out.println(
				"❌ Test failed in clickOnRowCellTagElement for " + rowId + "," + cellId + "," + tagName + " in " + url);
		return false;
	}

	/**
	 * Wait until an element with class "tr_checking2" exists, or wait until one doesn't.
	 *
	 * @param checking true to wait until such an element exists, or false to wait until no such element exists
	 * @param url the url we're loading
	 * @return true for success, false for failure
	 */
	private boolean waitUntilClassChecking(boolean checking, String url) {
		String className = "tr_checking2";
		try {
			wait.until(new ExpectedCondition<Boolean>() {
				@Override
				public Boolean apply(WebDriver webDriver) {
					// WebElement el = webDriver.findElement(By.className(className));
					// return checking ? (el != null) : (el == null);
					int elCount = webDriver.findElements(By.className(className)).size();
					return checking ? (elCount > 0) : (elCount == 0);
				}
			});
		} catch (Exception e) {
			System.out.println(e);
			System.out.println("❌ Test failed, maybe timed out, waiting for class " + className
					+ (checking ? "" : " not") + " to exist for " + url);
			return false;
		}
		return true;
	}
}
