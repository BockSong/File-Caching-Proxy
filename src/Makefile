all: Proxy.class Server.class

%.class: %.java
	javac $< -target 8 -source 8 -encoding ascii

clean:
	rm -f *.class

# -Xms5m # memory limitation
