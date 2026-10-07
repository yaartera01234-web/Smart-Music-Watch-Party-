#pragma once

#include <jni.h>

#include <string>

/*
 * Text between Java and mpv. mpv reads and writes standard UTF-8, while JNI's GetStringUTFChars
 * and NewStringUTF use Modified UTF-8, which writes a character outside the BMP as two surrogates.
 */

/* The UTF-8 bytes of a Java string. A lone surrogate becomes U+FFFD. Null gives an empty string. */
std::string utf8_from_jstring(JNIEnv *env, jstring s);

/* A Java string from UTF-8 bytes. An invalid sequence becomes U+FFFD. Null gives null. */
jstring jstring_from_utf8(JNIEnv *env, const char *s);
