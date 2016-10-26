/*
 Copyright (c) 2015, The Cinder Project

 This code is intended to be used with the Cinder C++ library, http://libcinder.org

 Redistribution and use in source and binary forms, with or without modification, are permitted provided that
 the following conditions are met:

 * Redistributions of source code must retain the above copyright notice, this list of conditions and
	the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and
	the following disclaimer in the documentation and/or other materials provided with the distribution.

 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 POSSIBILITY OF SUCH DAMAGE.
*/

#include "cinder/android/hardware/Vibrator.h"
#include "cinder/android/AndroidDevLog.h"
#include "cinder/android/app/CinderNativeActivity.h"
using namespace ci::android::app;

#include <sstream>

namespace cinder { namespace android { namespace hardware {

jclass 		Vibrator::Java::ClassObject			= nullptr;
jmethodID 	Vibrator::Java::create              = nullptr;
jmethodID 	Vibrator::Java::vibrate              = nullptr;
jmethodID 	Vibrator::Java::destroy				= nullptr;
jmethodID 	Vibrator::Java::stop				= nullptr;

Vibrator::Vibrator(jobject obj)
{
    mJavaObject = JniHelper::Get()->TrackedNewGlobalRef( obj );
    if( ! mJavaObject ) {
        throw std::runtime_error( "Error initializing mJavaObject (" + std::string( __PRETTY_FUNCTION__ ) + ")" );
    }
    JniHelper::Get()->CallVoidMethod( mJavaObject->getObject(), Java::create );
}

Vibrator::~Vibrator()
{
	JniHelper::Get()->TrackedDeleteGlobalRef( mJavaObject );
}

VibratorRef Vibrator::create( )
{
    jobject obj = ci::android::app::CinderNativeActivity::getJavaObject();
	VibratorRef result = VibratorRef( new Vibrator( obj ) );
	return result;
}


void Vibrator::cacheJni()
{
	if( JniHelper::Get()->AttachCurrentThread() ) {
		try {
            jclass javaClass = ci::android::app::CinderNativeActivity::getInstance()->getJavaClass();
            if( nullptr != javaClass ) {
                Java::ClassObject = reinterpret_cast<jclass>( JniHelper::Get()->NewGlobalRef( javaClass ) );
                jni_obtained_check( Vibrator::Java::ClassObject );
            }

			if( nullptr != javaClass ) {
                Java::create  = JniHelper::Get()->GetMethodId( javaClass, "hardware_vibrator_initialize", "()V" );
				Java::destroy = JniHelper::Get()->GetMethodId( javaClass, "hardware_vibrator_destroy", "()V" );
				Java::vibrate = JniHelper::Get()->GetMethodId( javaClass, "hardware_vibrator_vibrate", "(I)V" );
				Java::stop    = JniHelper::Get()->GetMethodId( javaClass, "hardware_vibrator_stop", "()V" );
                jni_obtained_check( Vibrator::Java::create );
                jni_obtained_check( Vibrator::Java::destroy );
				jni_obtained_check( Vibrator::Java::stop );
                jni_obtained_check( Vibrator::Java::vibrate );
			}
		}
		catch( const std::exception& e ) {
			dbg_app_error( std::string( "Vibrator::cacheJni error: " ) + e.what() );
		}
	}
}

void Vibrator::destroyJni()
{
	if( JniHelper::Get()->AttachCurrentThread() ) {	
		JniHelper::Get()->DeleteGlobalRef( Java::ClassObject  );
		Java::ClassObject			= nullptr;
		Java::destroy				= nullptr;
		Java::vibrate				= nullptr;
		Java::stop					= nullptr;
	}
}

void Vibrator::vibrate(int millis)
{
    JniHelper::Get()->CallVoidMethod( mJavaObject->getObject(), Java::vibrate, (jint) millis );
}

void Vibrator::stop()
{
	JniHelper::Get()->CallVoidMethod( mJavaObject->getObject(), Java::stop );
}

}}} // namespace cinder::android::hardware
