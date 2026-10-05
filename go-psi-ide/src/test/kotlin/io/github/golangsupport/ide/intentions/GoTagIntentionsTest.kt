package io.github.golangsupport.ide.intentions

import com.intellij.codeInsight.intention.PriorityAction

/** G4 struct tags: Add key to tags, Change field name style in tags (popups through the test hooks), Update key value in tags. */
class GoTagIntentionsTest : GoIntentionTestSupport() {

    override fun tearDown() {
        try {
            GoChangeTagNameStyleIntention.chooser = null
            GoAddTagKeyIntention.chooser = null
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun choose(label: String) {
        GoChangeTagNameStyleIntention.chooser = { labels ->
            assertEquals(listOf("full-name", "full_name", "FullName", "fullName"), labels)
            labels.indexOf(label)
        }
    }

    fun testChangeStyleToSnakeCase() {
        choose("full_name")
        doTest(
            """
            package p

            type User struct {
            	FullName string `json:"full<caret>Name"`
            	UserID   int    `json:"userID,omitempty" yaml:"userID"`
            	Skipped  bool   `json:"-"`
            	Plain    string
            }
            """,
            "Change field name style in tags",
            """
            package p

            type User struct {
            	FullName string `json:"full_name"`
            	UserID   int    `json:"user_id,omitempty" yaml:"userID"`
            	Skipped  bool   `json:"-"`
            	Plain    string
            }
            """,
        )
    }

    fun testChangeStyleFromTheTypeNameUsesTheFirstKey() {
        choose("full-name")
        doTest(
            """
            package p

            type Us<caret>er struct {
            	FullName string `yaml:"fullName" json:"fullName"`
            	Age      int    `yaml:"age"`
            }
            """,
            "Change field name style in tags",
            """
            package p

            type User struct {
            	FullName string `yaml:"full-name" json:"fullName"`
            	Age      int    `yaml:"age"`
            }
            """,
        )
    }

    fun testChangeStyleOfTheKeyAtTheCaretInAnInterpretedTag() {
        choose("FullName")
        doTest(
            """
            package p

            type User struct {
            	FullName string "json:\"full_name\" yaml:\"full<caret>_name\""
            }
            """,
            "Change field name style in tags",
            """
            package p

            type User struct {
            	FullName string "json:\"full_name\" yaml:\"FullName\""
            }
            """,
        )
    }

    fun testNoStyleChangeWithoutNamedKeys() = assertNotOffered(
        """
        package p

        type User struct {
        	FullName string `validate:"req<caret>uired"`
        }
        """,
        "Change field name style in tags",
    )

    fun testUpdateKeyValue() = doTest(
        """
        package p

        type User struct {
        	FullName string `json:"full_name"`
        	UserID   int    `json:"user_id"`
        	Email    string `json:"ma<caret>il,omitempty"`
        }
        """,
        "Update key value in tags",
        """
        package p

        type User struct {
        	FullName string `json:"full_name"`
        	UserID   int    `json:"user_id"`
        	Email    string `json:"email,omitempty"`
        }
        """,
    )

    // G10, seen live on GoLand 2026.2.3: offered on a matching tag too, where it changes nothing
    fun testUpdateOnAMatchingValueChangesNothing() = doTest(
        """
        package p

        type User struct {
        	FullName string `json:"full_name"`
        	Email    string `json:"em<caret>ail"`
        }
        """,
        "Update key value in tags",
        """
        package p

        type User struct {
        	FullName string `json:"full_name"`
        	Email    string `json:"email"`
        }
        """,
    )

    fun testNoUpdateWithoutANameKey() = assertNotOffered(
        """
        package p

        type User struct {
        	Email string `validate:"req<caret>uired"`
        }
        """,
        "Update key value in tags",
    )

    private val tagIntentions = listOf("Add key to tags", "Change field name style in tags", "Update key value in tags")

    private fun tagIntentionsAt(needle: String): List<String> {
        val text = "package p\n\ntype Probe2Config struct {\n\tName  string `json:\"Name\"`\n\tValue int    `json:\"value\"`\n}\n"
        val at = text.indexOf(needle)
        myFixture.configureByText("a.go", text.substring(0, at) + "<caret>" + text.substring(at))
        return myFixture.availableIntentions.map { it.text }.filter { it in tagIntentions }
    }

    // the probe of G10 (probe2/other.go): all three on the matching tag and on the other one; GoLand's order is all HIGH, then by text
    fun testAllThreeOnMatchingAndNonMatchingTags() {
        assertEquals(tagIntentions, tagIntentionsAt("value\"").sorted())
        assertEquals(tagIntentions, tagIntentionsAt("Name\"").sorted())
        for (intention in listOf(GoAddTagKeyIntention(), GoChangeTagNameStyleIntention(), GoUpdateTagKeyValueIntention())) {
            assertEquals(PriorityAction.Priority.HIGH, (intention as PriorityAction).priority)
        }
    }

    fun testAddKeyToAllFields() {
        GoAddTagKeyIntention.chooser = { keys ->
            assertEquals(listOf("json", "yaml", "xml", "toml", "db", "mapstructure", "bson", "env", "form"), keys)
            "yaml"
        }
        doTest(
            """
            package p

            type User struct {
            	FullName string `json:"full<caret>Name"`
            	UserID   int
            	Raw      string "json:\"raw\""
            	Empty    bool   ``
            	A, B     int
            	Embedded
            }
            """,
            "Add key to tags",
            """
            package p

            type User struct {
            	FullName string `json:"fullName" yaml:"fullname"`
            	UserID   int `yaml:"userid"`
            	Raw      string "json:\"raw\" yaml:\"raw\""
            	Empty    bool   `yaml:"empty"`
            	A, B     int
            	Embedded
            }
            """,
        )
    }

    fun testAddKeyUsesTheStyleOfTheKey() {
        GoAddTagKeyIntention.chooser = { "json" }
        doTest(
            """
            package p

            type User struct {
            	FullName string `json:"full_name"`
            	UserID   int    `yaml:"u<caret>serID"`
            }
            """,
            "Add key to tags",
            """
            package p

            type User struct {
            	FullName string `json:"full_name"`
            	UserID   int    `yaml:"userID" json:"user_id"`
            }
            """,
        )
    }

    fun testAddKeyFromTheTypeName() = assertOffered(
        """
        package p

        type Us<caret>er struct {
        	Name string
        }
        """,
        "Add key to tags",
    )

    fun testNoAddKeyOutsideAStruct() = assertNotOffered(
        """
        package p

        func f() { x<caret> := 1; _ = x }
        """,
        "Add key to tags",
    )
}
