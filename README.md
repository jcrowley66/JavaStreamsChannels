# jdcchannel

These utilities allow an InputStream or OutputStream to be converted to non-blocking Channels.

An internal buffer and Thread are used to de-couple the blocking InputStream and OutputStream from the corresponding Channel.

Additional utilities are provided to convert, for example, a Socket into a SocketChannel.
