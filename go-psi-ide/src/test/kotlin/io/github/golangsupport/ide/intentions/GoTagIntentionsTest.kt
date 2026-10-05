package io.github.golangsupport.ide.intentions

/** G4 struct tags: Change field name style in tags (popup through the test hook), Update key value in tags. */
class GoTagIntentionsTest : GoIntentionTestSupport() {

    override fun tearDown() {
        try {
            GoChangeTagNameStyleIntention.chooser = null
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

    fun testNoUpdateWhenTheValueMatches() = assertNotOffered(
        """
        package p

        type User struct {
        	FullName string `json:"full_name"`
        	Email    string `json:"em<caret>ail"`
        }
        """,
        "Update key value in tags",
    )
}
