package org.deeplearning4j.iterativereduce.actor.core.actor;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.iterativereduce.actor.core.FinetuneMessage;
import org.deeplearning4j.iterativereduce.actor.core.FinishMessage;
import org.deeplearning4j.iterativereduce.actor.core.ResetMessage;
import org.deeplearning4j.iterativereduce.actor.core.UpdateMessage;
import org.deeplearning4j.iterativereduce.actor.core.api.EpochDoneListener;
import org.deeplearning4j.nn.Persistable;
import org.deeplearning4j.scaleout.conf.Conf;
import org.deeplearning4j.scaleout.conf.DeepLearningConfigurable;
import org.deeplearning4j.scaleout.iterativereduce.ComputableMaster;
import org.deeplearning4j.scaleout.iterativereduce.Updateable;
import org.jblas.DoubleMatrix;

import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.OneForOneStrategy;
import akka.actor.SupervisorStrategy;
import akka.actor.SupervisorStrategy.Directive;
import akka.actor.UntypedActor;
import akka.cluster.Cluster;
import akka.contrib.pattern.DistributedPubSubExtension;
import akka.contrib.pattern.DistributedPubSubMediator;
import akka.contrib.pattern.DistributedPubSubMediator.Put;
import akka.dispatch.Futures;
import akka.dispatch.OnComplete;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.Function;

import com.google.common.collect.Lists;

/**
 * Handles a set of workers and acts as a parameter server for iterative reduce
 * @author Adam Gibson
 *
 */
public abstract class MasterActor<E extends Updateable<?>> extends UntypedActor implements DeepLearningConfigurable,ComputableMaster<E> {

	protected Conf conf;
	protected LoggingAdapter log = Logging.getLogger(getContext().system(), this);
	protected E masterResults;
	protected List<E> updates = new ArrayList<E>();
	protected EpochDoneListener<E> listener;
	protected ActorRef batchActor;
	protected int epochsComplete;
	protected final ActorRef mediator = DistributedPubSubExtension.get(getContext().system()).mediator();
	public static String BROADCAST = "broadcast";
	public static String MASTER = "result";
	public static String SHUTDOWN = "shutdown";
	public static String FINISH = "finish";
	Cluster cluster = Cluster.get(getContext().system());
	protected ScheduledExecutorService iterChecker;



	//number of batches over time
	protected int partition = 1;
	protected boolean isDone = false;


	/**
	 * Creates the master and the workers with this given conf
	 * @param conf the neural net config to use
	 */
	public MasterActor(Conf conf,ActorRef batchActor) {
		this.conf = conf;
		this.batchActor = batchActor;
		//subscribe to broadcasts from workers (location agnostic)
		mediator.tell(new Put(getSelf()), getSelf());

		mediator.tell(new DistributedPubSubMediator.Subscribe(MasterActor.MASTER, getSelf()), getSelf());
		mediator.tell(new DistributedPubSubMediator.Subscribe(MasterActor.FINISH, getSelf()), getSelf());
		setup(conf);
		iterChecker = Executors.newScheduledThreadPool(1);
		iterChecker.scheduleAtFixedRate(new Runnable() {

			@Override
			public void run() {
				log.info("Updating model...");
				File save = new File("nn-model.bin");
				if(save.exists()) {
					File parent = save.getParentFile();
					save.renameTo(new File(parent,save.getName() + "-" + System.currentTimeMillis()));
				}
				try {
					BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(save));
					Updateable<? extends Persistable> u = (Updateable<? extends Persistable>) masterResults;
					u.get().write(bos);
					bos.flush();
					bos.close();
					log.info("saved model to " + "nn-model.bin");

				}catch(Exception e) {
					throw new RuntimeException(e);
				}
				
				
			}

		}, 10,60, TimeUnit.SECONDS);


	}




	@Override
	public void preStart() throws Exception {
		super.preStart();
		log.info("Pre start on master");
	}




	@Override
	public void postStop() throws Exception {
		super.postStop();
		log.info("Post stop on master");
		cluster.unsubscribe(getSelf());
	}




	@Override
	public abstract E compute(Collection<E> workerUpdates,
			Collection<E> masterUpdates);

	@Override
	public abstract void setup(Conf conf);


	@SuppressWarnings({ "unchecked" })
	@Override
	public void onReceive(Object message) throws Exception {
		if (message instanceof DistributedPubSubMediator.SubscribeAck) {
			DistributedPubSubMediator.SubscribeAck ack = (DistributedPubSubMediator.SubscribeAck) message;
			log.info("Subscribed " + ack.toString());
		}
		else if(message instanceof EpochDoneListener) {
			listener = (EpochDoneListener<E>) message;
			log.info("Set listener");
		}

		else if(message instanceof Updateable) {
			E up = (E) message;
			updates.add(up);
			if(updates.size() >= partition) {
				masterResults = this.compute(updates, updates);
				log.info("Saving new model");
				mediator.tell(new DistributedPubSubMediator.Publish(BatchActor.FINETUNE,
						new FinetuneMessage(masterResults)), mediator);
				
				//reset the dataset
				batchActor.tell(new ResetMessage(), getSelf());
				epochsComplete++;
				batchActor.tell(up, getSelf());
				updates.clear();

				if(epochsComplete == conf.getNumPasses()) {
					isDone = true;
					/*log.info("All done; shutting down");
					//send a shutdown signal
					mediator.tell(new DistributedPubSubMediator.Publish(SHUTDOWN,
							new ShutdownMessage()), getSelf());*/
					//Cluster.get(this.getContext().system()).down(Cluster.get(getContext().system()).selfAddress());

				}

			}

		}
		else if(message instanceof FinishMessage) {
			if(!updates.isEmpty()) {
				masterResults = this.compute(updates, updates);
				if(listener != null)
					listener.epochComplete(masterResults);

			}

			isDone = true;
			log.info("All done; shutting down");
			//send a shutdown signal
			/*mediator.tell(new DistributedPubSubMediator.Publish(SHUTDOWN,
					new ShutdownMessage()), getSelf());
			Cluster.get(this.getContext().system()).down(Cluster.get(getContext().system()).selfAddress());*/

		}

		//broadcast new weights to workers
		else if(message instanceof UpdateMessage) {
			mediator.tell(new DistributedPubSubMediator.Publish(BROADCAST,
					message), getSelf());
		}


		//list of examples
		else if(message instanceof List || message instanceof Pair) {

			if(message instanceof List) {
				List<Pair<DoubleMatrix,DoubleMatrix>> list = (List<Pair<DoubleMatrix,DoubleMatrix>>) message;
				//each pair in the matrix pairs maybe multiple rows
				splitListIntoRows(list);
				//delegate split to workers
				sendToWorkers(list);

			}

			//ensure split then send to workers
			else if(message instanceof Pair) {
				Pair<DoubleMatrix,DoubleMatrix> pair = (Pair<DoubleMatrix,DoubleMatrix>) message;

				//split pair up in to rows to ensure parallelism
				List<DoubleMatrix> inputs = pair.getFirst().rowsAsList();
				List<DoubleMatrix> labels = pair.getSecond().rowsAsList();

				List<Pair<DoubleMatrix,DoubleMatrix>> pairs = new ArrayList<>();
				for(int i = 0; i < inputs.size(); i++) {
					pairs.add(new Pair<>(inputs.get(i),labels.get(i)));
				}


				sendToWorkers(pairs);

			}
		}

		else
			unhandled(message);
	}


	protected void sendToWorkers(List<Pair<DoubleMatrix,DoubleMatrix>> pairs) {
		int split = conf.getSplit();
		final List<List<Pair<DoubleMatrix,DoubleMatrix>>> splitList = Lists.partition(pairs, split);
		partition = splitList.size();
		log.info("Found partition of size " + partition);
		for(int i = 0; i < splitList.size(); i++)  {
			final int j = i;
			Future<Void> f = Futures.future(new Callable<Void>() {

				@Override
				public Void call() throws Exception {
					log.info("Sending off work for batch " + j);
					mediator.tell(new DistributedPubSubMediator.Publish(BROADCAST,
							new ArrayList<>(splitList.get(j))), getSelf());
					return null;
				}

			},context().dispatcher());
			
			f.onComplete(new OnComplete<Void>() {

				@Override
				public void onComplete(Throwable arg0, Void arg1)
						throws Throwable {
					if(arg0 != null)
						throw arg0;
				}
				
			}, context().dispatcher());
			
			
		}


	}



	protected void splitListIntoRows(List<Pair<DoubleMatrix,DoubleMatrix>> list) {
		Queue<Pair<DoubleMatrix,DoubleMatrix>> q = new ArrayDeque<>(list);
		list.clear();
		log.info("Splitting list in to rows...");
		while(!q.isEmpty()) {
			Pair<DoubleMatrix,DoubleMatrix> pair = q.poll();
			List<DoubleMatrix> inputRows = pair.getFirst().rowsAsList();
			List<DoubleMatrix> labelRows = pair.getSecond().rowsAsList();
			if(inputRows.isEmpty())
				throw new IllegalArgumentException("No input rows found");
			if(inputRows.size() != labelRows.size())
				throw new IllegalArgumentException("Label rows not equal to input rows");

			for(int i = 0; i < inputRows.size(); i++) {
				list.add(new Pair<DoubleMatrix,DoubleMatrix>(inputRows.get(i),labelRows.get(i)));
			}
		}
	}


	public static abstract class MasterActorFactory<E> implements Creator<MasterActor<Updateable<E>>> {

		public MasterActorFactory(Conf conf,ActorRef batchActor) {
			this.conf = conf;
			this.batchActor = batchActor;
		}

		protected Conf conf;
		protected ActorRef batchActor;
		/**
		 * 
		 */
		private static final long serialVersionUID = 1932205634961409897L;

		@Override
		public abstract MasterActor<Updateable<E>> create() throws Exception;



	}

	@Override
	public abstract void complete(DataOutputStream ds);

	@Override
	public E getResults() {
		return masterResults;
	}

	@Override
	public SupervisorStrategy supervisorStrategy() {
		return new OneForOneStrategy(0, Duration.Zero(),
				new Function<Throwable, Directive>() {
			public Directive apply(Throwable cause) {
				log.error("Problem with processing",cause);
				return SupervisorStrategy.stop();
			}
		});
	}

	public Conf getConf() {
		return conf;
	}

	public int getEpochsComplete() {
		return epochsComplete;
	}

	public int getPartition() {
		return partition;
	}

	public E getMasterResults() {
		return masterResults;
	}

	public boolean isDone() {
		return isDone;
	}


	public List<E> getUpdates() {
		return updates;
	}




	public EpochDoneListener<E> getListener() {
		return listener;
	}




	public ActorRef getBatchActor() {
		return batchActor;
	}




	public ActorRef getMediator() {
		return mediator;
	}




	public static String getBROADCAST() {
		return BROADCAST;
	}




	public static String getRESULT() {
		return MASTER;
	}


}
