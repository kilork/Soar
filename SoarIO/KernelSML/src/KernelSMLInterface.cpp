/////////////////////////////////////////////////////////////////
// KernelSMLInterface file.
//
// Author: Douglas Pearson, www.threepenny.net
// Date  : August 2004
//
// This file provides a C level interface into the KernelSML library and
// implements "EmbeddedSMLInterface.h" (so there is no KernelSMLInteface.h file).
//
// KernelSML receives commands in SML (a dialect of XML), sends them to the Soar kernel
// and then returns the results in SML.
//
// The SML can be passed directly as an object into this library (if the client and kernel happen
// to be in the same process) or the SML can be sent as a regular XML stream.
//
/////////////////////////////////////////////////////////////////

#include "EmbeddedSMLInterface.h"
#include "sml_Connection.h"
#include "sml_EmbeddedConnection.h"
#include "sml_ElementXML.h"
#include "sml_Names.h"
#include "sml_KernelSML.h"

using namespace sml ;

ElementXML* ReceivedCall(Connection* pConnection, ElementXML* pIncoming, void* pUserData)
{
	unused(pUserData) ;

	// This must be initialized when the connection was created.
	KernelSML* pKernel = (KernelSML*)pConnection->GetUserData() ;

	return pKernel->ProcessIncomingSML(pConnection, pIncoming) ;
}

static EmbeddedConnection* GetConnectionFromHandle(Connection_Receiver_Handle hConnection)
{
	return (EmbeddedConnection*)hConnection ;
}

EXPORT Connection_Receiver_Handle sml_CreateEmbeddedConnection(Connection_Sender_Handle hSenderConnection, ProcessMessageFunction pProcessMessage, int connectionType)
{
	// Create a connection object which we'll use to talk back to this sender
	EmbeddedConnection* pConnection = connectionType == SML_SYNCH_CONNECTION ?
										EmbeddedConnectionSynch::CreateEmbeddedConnectionSynch() :
										EmbeddedConnectionAsynch::CreateEmbeddedConnectionAsynch() ;

	// Record our kernel object with this connection.  I think we only want one kernel
	// object even if there are many connections (because there's only one kernel) so for now
	// that's how things are set up.
	KernelSML* pKernelSML = KernelSML::GetKernelSML() ;
	pConnection->SetUserData(pKernelSML) ;

	// Record this as one of the active connections
	pKernelSML->AddConnection(pConnection) ;

	// If this is a synchronous connection then commands will execute on the embedded client's thread
	// and we don't use the receiver thread.  (Why not?  If we allowed it to run then we'd have to (a)
	// sychronize execution between the two threads and (b) sometimes Soar would be running in the client's thread and
	// sometimes in the receiver's thread (depending on where "run" came from) and that could easily introduce a lot of
	// complicated bugs or where performance would be different depending on whether you pressed "run" in the environment or "run" in a
	// remote debugger).
	pKernelSML->StopReceiverThread() ;

	// Register for "calls" from the client.
	pConnection->RegisterCallback(ReceivedCall, NULL, sml_Names::kDocType_Call, true) ;

	// The original sender is a receiver to us so we need to reverse the type.
	pConnection->AttachConnectionInternal((Connection_Receiver_Handle)hSenderConnection, pProcessMessage) ;

	return (Connection_Receiver_Handle)pConnection ;
}

EXPORT ElementXML_Handle sml_ProcessMessage(Connection_Receiver_Handle hReceiverConnection, ElementXML_Handle hIncomingMsg, int action)
{
	EmbeddedConnection* pConnection = GetConnectionFromHandle(hReceiverConnection) ;

	if (action == SML_MESSAGE_ACTION_CLOSE)
	{
		if (pConnection)
		{
			// Close our connection to the remote process
			pConnection->ClearConnectionHandle() ;

			// When the embedded connection disconnects we're about to exit the application
			// so shutdown any remote connections cleanly and do any other cleanup.
			KernelSML* pKernelSML = KernelSML::GetKernelSML() ;
			pKernelSML->Shutdown() ;

			// The shutdown call above will also delete our connection object as part of its cleanup
			// so set it to NULL here to make sure we don't try to use it again.
			pConnection = NULL ;
		}

		return NULL ;
	}

	if (action == SML_MESSAGE_ACTION_SYNCH)
	{
		// Create an object to wrap this message.
		// When this object is deleted, it releases our reference to this handle.
		ElementXML incomingMsg(hIncomingMsg) ;

		ElementXML* pResponse = pConnection->InvokeCallbacks(&incomingMsg) ;

		if (!pResponse)
			return NULL ;

		ElementXML_Handle hResponse = pResponse->Detach() ;
		delete pResponse ;
		return hResponse ;
	}

	if (action == SML_MESSAGE_ACTION_ASYNCH)
	{
		// Store the incoming message on a queue and execute it on the receiver's thread (our thread) at a later point.
		ElementXML* pIncomingMsg = new ElementXML(hIncomingMsg) ;

		pConnection->AddToIncomingMessageQueue(pIncomingMsg) ;

		// There is no immediate response to an asynch message.
		// The response will be sent back to the caller as another asynch message later, once the command has been executed.
		return NULL ;
	}

	// Not an action we understand, so just ignore it.
	return NULL ;
}
