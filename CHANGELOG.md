Changelog
=========

Next version
-----
 * fix 51
 * add ofStatement definition to @Default annotation

2.6.0
-----
 * fix #48
 * fix #47, use same visibility for generated preferences as interface
 * add char and byte support
 * update dependencies

2.5.2
-----
 * simple set and get methods via name of preference
 * Add support for Arrays

2.5.1
-----
 * class default for complex types (proved class will be instantiated with default constructor)

2.5.0
-----
 * internal refactoring
 * generate String constants of preference names
 * possibility to add and remove values from Collection types via $Add and $Remove prefixes

2.4.1
-----
 * fix warning message
 * automatic cache size

2.4.0
-----
 * fix #41, #38
 * add resetCache method on CacheActions
 * fix NullPointerException in cache
 * warn when caching default SharedPreferences
 * experimental String resource generation

2.3.1
-----
 * fix #37
 * update dependencies

2.3.0
-----
 * generated container classes implement the Serializable interface
 * caching via @Cached annotation

2.2.0
-----
 * fix for issue #29: search JacksonSerializer automatically on classpath
 * do not generate API Level checks
 * dropped complete support for API < 9
 * update dependencies

2.1.0
-----
 * fix for issue #23 by wrapping generics into a container
 * bumped dependency versions (this fixes #25)
 * new SharedPreferenceActions action to clear only values that were explicitly defined in the interface

2.0.0
-----
 * Allow getter with a runtime default by appending the `$Default` suffix
 * Add Jackson Serializer plugin

1.1.2
-----
 * small follow-up bugfix regarding #16

1.1.1
-----
 * fix #16
 * add prefix for esperandro compiler messages
 * update dependencies

1.1
---
 * changed default file system sync of preferences from commit() to apply(). Reduces load on the UI Thread.
 * added support for setters with boolean return type. Those still use commit() to be able to tell about the success of the operation
 * added action "initDefaults". Can be used to initialize default values for immediate access in PreferenceActivities
 * circumvent exception when using maven and robolectric

1.0
---
 * updated JavaWriter to 2.1.2
 * added remove functionality in SharedPreferenceActions
 * minor bugfix when using generics as serializable Objects

0.13
----
 * allow storing and accessing of serializable Objects into the preferences
 * fixed interface inheritance for library projects in eclipse and for super interfaces in jars

0.12
----
 * added interface inheritance

0.11
----
 * bugfixes

0.10
----
 * bugfixes

0.9
---
 * intial release
