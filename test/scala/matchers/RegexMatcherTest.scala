package matchers

import model._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import com.softwaremill.diffx.scalatest.DiffMatcher._

import services.MatcherRequest
import utils.Text

class RegexMatcherTest extends AsyncFlatSpec with Matchers {
  def createRules(textsToMatch: List[String]) = {
    textsToMatch.zipWithIndex.map { case (text, index) =>
      RegexRule(
        id = s"example-rule-$index",
        category = Category("new-category", "New Category"),
        description = s"Example rule $index",
        suggestions = List(TextSuggestion(s"Suggestion for rule $index")),
        regex = text.r
      )
    }
  }
  val exampleCategory = Category("example-category", "Example category")
  val exampleRule = createRules(List("text"))(0)

  val regexValidator = new RegexMatcher(exampleCategory, List(exampleRule))

  def getBlocks(text: String) = List(TextBlock("text-block-id", text, 0, text.length))

  def getMatch(text: String, fromPos: Int, toPos: Int, before: String, after: String, rule: RegexRule = exampleRule, replacement: Option[String] = None) = RuleMatch(
    rule = rule,
    fromPos = fromPos,
    toPos = toPos,
    before = before,
    after = after,
    matchedText = text,
    message = rule.description,
    shortMessage = Some(rule.description),
    suggestions = rule.suggestions,
    replacement = replacement.map(TextSuggestion(_)),
    matchContext = Text.getMatchTextSnippet(before, text, after),
    matcherType = RegexMatcher.getType()
  )


  "check" should "report single matches in short text" in {
    val sampleText = "example text is here"

    val eventuallyMatches = regexValidator.check(
      MatcherRequest(getBlocks(sampleText))
    )
    eventuallyMatches.map { matches =>
      matches should matchTo(List(
        getMatch("text", 8, 12, "example ", " is here")
      ))
    }
  }

  "check" should "report single matches in long text" in {
    val sampleText = """
                       | 123456789 123456789 123456789
                       | 123456789 123456789 123456789
                       | 123456789 123456789 123456789
                       | 123456789 123456789 123456789
                       | text
                       | 123456789 123456789 123456789
                       | 123456789 123456789 123456789
                       | 123456789 123456789 123456789
                       | 123456789 123456789 123456789
                       |""".stripMargin.replace("\n", "")

    val before = """
                       |123456789
                       | 123456789 123456789 123456789
                       | 123456789 123456789 123456789
                       | 123456789 123456789 123456789
                       | """.stripMargin.replace("\n", "")
    val after = """
                       | 123456789 123456789 123456789
                       | 123456789 123456789 123456789
                       | 123456789 123456789 123456789
                       | 123456789
                       |""".stripMargin.replace("\n", "")

    val eventuallyMatches = regexValidator.check(
      MatcherRequest(getBlocks(sampleText))
    )
    eventuallyMatches.map { matches =>
      matches should matchTo(List(
        getMatch("text", 121, 125, before, after)
      ))
    }
  }

  "check" should "report multiple matches" in {
    val eventuallyMatches = regexValidator.check(
      MatcherRequest(getBlocks("text text text"))
    )
    eventuallyMatches.map { matches =>
      matches should matchTo(List(
        getMatch("text", 0, 4, "", " text text"),
        getMatch("text", 5, 9, "text ", " text"),
        getMatch("text", 10, 14, "text text ", "")
      ))
    }
  }

  "check" should "supersede matches generated by early rules if they overlap with matches generated by later rules" in {
    val overlapRules = createRules(List("to", "ton", "one", "got"))
    val overlapValidator = new RegexMatcher(exampleCategory, overlapRules)
    val eventuallyMatches = overlapValidator.check(
      MatcherRequest(getBlocks("tone ton goto"))
    )
    eventuallyMatches.map { matches =>
      matches.size shouldBe 3
      matches(0) should matchTo(getMatch("ton", 5, 8, "tone ", " goto", overlapRules(1)))
      matches(1) should matchTo(getMatch("one", 1, 4, "t", " ton goto", overlapRules(2)))
      matches(2) should matchTo(getMatch("got", 9, 12, "tone ton ", "o", overlapRules(3)))
    }
  }

  "check" should "use substitions when generating replacements" in {
    val rule = RegexRule(
      id = s"example-rule",
      category = Category("new-category", "New Category"),
      description = s"Example rule",
      replacement = Some(TextSuggestion("tea$1")),
      regex = "\\btea-? ?(shop|bag|leaf|leaves|pot)".r
    )

    val validator = new RegexMatcher(exampleCategory, List(rule))
    val eventuallyMatches = validator.check(
      MatcherRequest(getBlocks("I'm a little tea pot"))
    )

    eventuallyMatches.map { matches =>
      matches.size shouldBe 1
      val expectedReplacement = Some("teapot")
      val expectedMatch = getMatch("tea pot", 13, 20, "I'm a little ", "", rule, expectedReplacement)
      matches(0) should matchTo(expectedMatch)
    }
  }

   "check" should "handle multiple substitions" in {
    val rule = RegexRule(
      id = s"example-rule",
      category = Category("new-category", "New Category"),
      description = s"Example rule",
      replacement = Some(TextSuggestion("$1-$2-long")),
      regex = "\\b(one|two|three|four|five|six|seven|eight|nine|\\d)-? (year|day|month|week|mile)-? long".r
    )

    val validator = new RegexMatcher(exampleCategory, List(rule))
    val eventuallyMatches = validator.check(
      MatcherRequest(getBlocks("A nine month long sabbatical"))
    )

    eventuallyMatches.map { matches =>
      matches.size shouldBe 1
      val expectedReplacement = Some("nine-month-long")
      val expectedMatch = getMatch("nine month long", 2, 17, "A ", " sabbatical", rule, expectedReplacement)
      matches(0) should matchTo(expectedMatch)
    }
  }
}
